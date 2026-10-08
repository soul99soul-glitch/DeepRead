import XCTest
@testable import AmberDeepRead

@MainActor
final class DeepReadDiscoveryReviewTests: XCTestCase {
    func testRSSPlainAndCDATATitlesKeepTheirArticleLinks() {
        let provider = IOSRSSHotlistProvider(providerId: "arxiv_ai", displayName: "arXiv", feedURL: "https://example.com/feed")
        let xml = """
        <rss><channel>
          <item><title>AI breakthrough</title><link>https://example.com/ai</link></item>
          <item><title><![CDATA[Models at work]]></title><link><![CDATA[https://example.com/models]]></link></item>
        </channel></rss>
        """
        let items = provider.parse(Data(xml.utf8), limit: 20)
        XCTAssertEqual(items.map(\.title), ["AI breakthrough", "Models at work"])
        XCTAssertEqual(items.map(\.url), ["https://example.com/ai", "https://example.com/models"])
        XCTAssertEqual(items.map(\.rank), [1, 2])
    }

    func testFifteenMinuteSelectionRefreshesTwentyMinuteOldCache() throws {
        let root = try makeCache(ageMinutes: 20)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = IOSHotListDashboardStore(baseDirectory: root)
        var setting = fixtureSetting()
        setting.hotListRefreshIntervalMinutes = 15
        XCTAssertTrue(store.shouldRefresh(setting: setting))
        setting.hotListRefreshIntervalMinutes = 30
        XCTAssertFalse(store.shouldRefresh(setting: setting))
    }

    func testOfflineSettingsProjectSourcesFocusAndTranslationWithoutModelRequests() async throws {
        let root = try makeCache(translated: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let before = try Data(contentsOf: cacheFile(root))
        let store = IOSHotListDashboardStore(baseDirectory: root)
        var setting = fixtureSetting()
        setting.hotListWifiOnly = true
        setting.hotListEnabledSources = ["hacker_news"]
        setting.hotListFocusKeywords = ["人工智能"]
        setting.hotListFilterMode = .focusOnly
        let translator: IOSHotListTitleTranslate = { _ in
            XCTFail("offline projection must not request a model")
            return [:]
        }
        let fetched = await store.refreshDiscovery(setting: setting, force: true, translate: translator, canFetch: { false })
        XCTAssertEqual(fetched, false)
        XCTAssertEqual(store.dashboard.providers.map(\.providerId), ["hacker_news"])
        XCTAssertEqual(store.dashboard.providers.flatMap(\.items).map(\.presentationTitle), ["人工智能突破"])

        setting.hotListTranslateToChinese = false
        setting.hotListFocusKeywords = ["AI"]
        _ = await store.refreshDiscovery(setting: setting, force: true, translate: translator, canFetch: { false })
        XCTAssertEqual(store.dashboard.providers.flatMap(\.items).map(\.presentationTitle), ["AI breakthrough"])

        setting.hotListEnabledSources = []
        _ = await store.refreshDiscovery(setting: setting, force: true, translate: translator, canFetch: { false })
        XCTAssertTrue(store.dashboard.providers.isEmpty)
        XCTAssertTrue(store.dashboard.topics.isEmpty)
        XCTAssertEqual(store.dashboard.enabledSourceCount, 0)
        XCTAssertFalse(store.dashboard.hasContent)
        XCTAssertEqual(try Data(contentsOf: cacheFile(root)), before, "local projection must retain the unfiltered disk cache")
    }

    func testCancelledTranslationDoesNotDiscardTheNextConfigurationRefresh() async throws {
        let root = try makeCache()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = IOSHotListDashboardStore(baseDirectory: root)
        var response: CheckedContinuation<[String: String], Never>?
        let first = Task {
            await store.refreshDiscovery(setting: fixtureSetting(), force: false, translate: { _ in
                await withCheckedContinuation { response = $0 }
            })
        }
        while response == nil { await Task.yield() }
        first.cancel()

        var disabled = fixtureSetting()
        disabled.hotListEnabledSources = []
        var secondStarted = false
        let second = Task {
            secondStarted = true
            return await store.refreshDiscovery(setting: disabled, force: false)
        }
        while !secondStarted { await Task.yield() }
        await Task.yield()
        response?.resume(returning: ["AI breakthrough": "人工智能突破"])
        let firstResult = await first.value
        let secondResult = await second.value

        XCTAssertNil(firstResult, "cancellation is separate from a rejected network gate")
        XCTAssertEqual(secondResult, true)

        XCTAssertEqual(store.dashboard, .empty)
        XCTAssertFalse(store.isRefreshing)
        let persisted = try JSONDecoder().decode(IOSHotListDashboard.self, from: Data(contentsOf: cacheFile(root)))
        XCTAssertEqual(persisted, .empty)
    }

    func testRapidConfigurationChangesFinishWithTheLatestSettings() async throws {
        let root = try makeCache()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = IOSHotListDashboardStore(baseDirectory: root)
        var response: CheckedContinuation<[String: String], Never>?
        let first = Task {
            await store.refreshDiscovery(setting: fixtureSetting(), force: false, translate: { _ in
                await withCheckedContinuation { response = $0 }
            })
        }
        while response == nil { await Task.yield() }

        var intermediate = fixtureSetting()
        intermediate.hotListFocusKeywords = ["AI"]
        intermediate.hotListFilterMode = .focusOnly
        var secondStarted = false
        let second = Task {
            secondStarted = true
            return await store.refreshDiscovery(setting: intermediate, force: false)
        }
        while !secondStarted { await Task.yield() }
        await Task.yield()
        var latest = fixtureSetting()
        latest.hotListEnabledSources = []
        var thirdStarted = false
        let third = Task {
            thirdStarted = true
            return await store.refreshDiscovery(setting: latest, force: false)
        }
        while !thirdStarted { await Task.yield() }
        await Task.yield()
        response?.resume(returning: ["AI breakthrough": "人工智能突破"])
        _ = await first.value
        _ = await second.value
        _ = await third.value

        XCTAssertEqual(store.dashboard, .empty)
        XCTAssertFalse(store.isRefreshing)
    }

    private func fixtureSetting() -> TodayBoardSetting {
        TodayBoardSetting(hotListEnabledSources: ["hacker_news", "arxiv_ai"],
                          hotListFocusKeywords: [], hotListFilterMode: .all,
                          hotListTranslateToChinese: true)
    }

    private func makeCache(translated: Bool = false, ageMinutes: Int = 0) throws -> URL {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("DeepReadDiscoveryReviewTests-\(UUID())")
        let file = cacheFile(root)
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        let now = IOSHotListClock.currentEpochMs() - Int64(ageMinutes) * 60_000
        let providers = [
            IOSHotListProviderSnapshot(providerId: "hacker_news", providerName: "Hacker News", items: [
                IOSHotlistItem(providerId: "hacker_news", title: "AI breakthrough", rank: 1, fetchedAt: now,
                               displayTitle: translated ? "人工智能突破" : nil)
            ], fetchedAt: now),
            IOSHotListProviderSnapshot(providerId: "arxiv_ai", providerName: "arXiv", items: [
                IOSHotlistItem(providerId: "arxiv_ai", title: "Better batteries", rank: 1, fetchedAt: now,
                               displayTitle: translated ? "电池进展" : nil)
            ], fetchedAt: now)
        ]
        let raw = IOSHotListDashboard(topics: IOSHotListAggregator.aggregate(providerSnapshots: providers),
                                      providers: providers, lastUpdatedAt: now, enabledSourceCount: 2)
        try JSONEncoder().encode(raw).write(to: file)
        return root
    }

    private func cacheFile(_ root: URL) -> URL { root.appendingPathComponent("deep_read/hotlist_dashboard.json") }
}
