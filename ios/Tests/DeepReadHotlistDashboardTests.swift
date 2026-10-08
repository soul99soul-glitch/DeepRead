import XCTest
import Observation
import Synchronization
@testable import AmberDeepRead

@MainActor
final class DeepReadHotlistDashboardTests: XCTestCase {
    func testUnchangedCachedRefreshDoesNotPublishOrRewriteCache() async throws {
        let root = try makeCache()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = IOSHotListDashboardStore(baseDirectory: root)
        let setting = fixtureSetting()
        await store.refresh(setting: setting, force: false)
        let published = store.dashboard
        let file = cacheFile(root)
        let oldDate = Date(timeIntervalSince1970: 1_234_567)
        try FileManager.default.setAttributes([.modificationDate: oldDate], ofItemAtPath: file.path)
        let publications = Mutex(0)
        withObservationTracking {
            _ = store.dashboard
        } onChange: {
            publications.withLock { $0 += 1 }
        }

        for _ in 0..<5 {
            await store.refresh(setting: setting, force: false, translate: { _ in
                XCTFail("already translated titles must not request translation")
                return [:]
            })
        }

        XCTAssertEqual(store.dashboard, published)
        XCTAssertEqual(publications.withLock { $0 }, 0)
        XCTAssertEqual(try modificationDate(file), oldDate)
        XCTAssertFalse(store.isRefreshing)
    }

    func testCachedSettingAndLimitChangesProjectWithoutLosingRawTranslations() async throws {
        let root = try makeCache()
        defer { try? FileManager.default.removeItem(at: root) }
        let before = try Data(contentsOf: cacheFile(root))
        let store = IOSHotListDashboardStore(baseDirectory: root)
        var setting = fixtureSetting()
        setting.hotListFocusKeywords = ["人工智能"]
        setting.hotListFilterMode = .focusOnly
        await store.refresh(setting: setting, force: false)
        XCTAssertEqual(store.dashboard.providers.flatMap(\.items).map(\.presentationTitle), ["人工智能突破"])

        setting.hotListTranslateToChinese = false
        await store.refresh(setting: setting, force: false)
        XCTAssertTrue(store.dashboard.topics.isEmpty)
        XCTAssertTrue(store.dashboard.providers.allSatisfy { $0.items.isEmpty })

        setting.hotListTranslateToChinese = true
        await store.refresh(setting: setting, force: false)
        XCTAssertEqual(store.dashboard.providers.flatMap(\.items).map(\.presentationTitle), ["人工智能突破"])

        setting.hotListFilterMode = .all
        await store.applyCached(setting: setting, limit: 1)
        XCTAssertEqual(store.dashboard.topics.count, 1)
        await store.applyCached(setting: setting, limit: 20)
        XCTAssertEqual(store.dashboard.topics.count, 3)
        setting.hotListEnabledSources = ["arxiv_ai"]
        await store.applyCached(setting: setting)
        XCTAssertEqual(store.dashboard.providers.map(\.providerId), ["arxiv_ai"])
        XCTAssertEqual(store.dashboard.enabledSourceCount, 1)
        XCTAssertEqual(try Data(contentsOf: cacheFile(root)), before)
    }

    func testNewTranslationPersistsUnfilteredDataAndReusesItOnNextRefresh() async throws {
        let root = try makeCache(translated: false)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = IOSHotListDashboardStore(baseDirectory: root)
        var setting = fixtureSetting()
        setting.hotListFocusKeywords = ["人工智能"]
        setting.hotListFilterMode = .focusOnly
        var calls = 0
        await store.refresh(setting: setting, force: false, translate: { titles in
            calls += 1
            XCTAssertEqual(Set(titles), ["AI breakthrough", "Databases for everyone", "Better batteries"])
            return ["AI breakthrough": "人工智能突破", "Databases for everyone": "数据库普及", "Better batteries": "电池进展"]
        })
        XCTAssertEqual(calls, 1)
        XCTAssertEqual(store.dashboard.providers.flatMap(\.items).count, 1)
        let persisted = try JSONDecoder().decode(IOSHotListDashboard.self, from: Data(contentsOf: cacheFile(root)))
        XCTAssertEqual(persisted.providers.flatMap(\.items).count, 3)
        XCTAssertTrue(persisted.providers.flatMap(\.items).allSatisfy { $0.displayTitle != nil })
        await store.refresh(setting: setting, force: false, translate: { _ in
            calls += 1
            return [:]
        })
        XCTAssertEqual(calls, 1)
    }

    func testCancelledCachedTranslationDoesNotPublishOrPersist() async throws {
        let root = try makeCache(translated: false)
        defer { try? FileManager.default.removeItem(at: root) }
        let before = try Data(contentsOf: cacheFile(root))
        let store = IOSHotListDashboardStore(baseDirectory: root)
        let initial = store.dashboard
        var response: CheckedContinuation<[String: String], Never>?
        let refresh = Task {
            await store.refresh(setting: fixtureSetting(), force: false, translate: { _ in
                await withCheckedContinuation { response = $0 }
            })
        }
        while response == nil { await Task.yield() }
        refresh.cancel()
        response?.resume(returning: ["AI breakthrough": "人工智能突破"])
        await refresh.value
        XCTAssertEqual(store.dashboard, initial)
        XCTAssertEqual(try Data(contentsOf: cacheFile(root)), before)
        XCTAssertFalse(store.isRefreshing)
    }

    func testDisabledSourcesClearAndPersistRawCache() async throws {
        let root = try makeCache()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = IOSHotListDashboardStore(baseDirectory: root)
        var setting = fixtureSetting()
        setting.hotListEnabledSources = []
        await store.refresh(setting: setting, force: false)
        XCTAssertEqual(store.dashboard, .empty)
        let persisted = try JSONDecoder().decode(IOSHotListDashboard.self, from: Data(contentsOf: cacheFile(root)))
        XCTAssertEqual(persisted, .empty)
        let oldDate = Date(timeIntervalSince1970: 1_234_567)
        try FileManager.default.setAttributes([.modificationDate: oldDate], ofItemAtPath: cacheFile(root).path)
        await store.refresh(setting: setting, force: false)
        XCTAssertEqual(try modificationDate(cacheFile(root)), oldDate)
    }

    private func fixtureSetting() -> TodayBoardSetting {
        TodayBoardSetting(hotListEnabledSources: ["hacker_news", "arxiv_ai"],
                          hotListFocusKeywords: [], hotListFilterMode: .all,
                          hotListTranslateToChinese: true)
    }

    private func makeCache(translated: Bool = true) throws -> URL {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("DeepReadHotlistDashboardTests-\(UUID())")
        let file = cacheFile(root)
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        let now = IOSHotListClock.currentEpochMs()
        let providers = [
            IOSHotListProviderSnapshot(providerId: "hacker_news", providerName: "Hacker News", items: [
                IOSHotlistItem(providerId: "hacker_news", title: "AI breakthrough", rank: 1,
                               fetchedAt: now, displayTitle: translated ? "人工智能突破" : nil),
                IOSHotlistItem(providerId: "hacker_news", title: "Databases for everyone", rank: 2,
                               fetchedAt: now, displayTitle: translated ? "数据库普及" : nil)
            ], fetchedAt: now),
            IOSHotListProviderSnapshot(providerId: "arxiv_ai", providerName: "arXiv", items: [
                IOSHotlistItem(providerId: "arxiv_ai", title: "Better batteries", rank: 1,
                               fetchedAt: now, displayTitle: translated ? "电池进展" : nil)
            ], fetchedAt: now)
        ]
        let raw = IOSHotListDashboard(topics: IOSHotListAggregator.aggregate(providerSnapshots: providers),
                                      providers: providers, lastUpdatedAt: now, enabledSourceCount: 2)
        try JSONEncoder().encode(raw).write(to: file)
        return root
    }

    private func cacheFile(_ root: URL) -> URL {
        root.appendingPathComponent("deep_read/hotlist_dashboard.json")
    }

    private func modificationDate(_ file: URL) throws -> Date? {
        try FileManager.default.attributesOfItem(atPath: file.path)[.modificationDate] as? Date
    }
}
