import XCTest
@testable import AmberDeepRead

final class DeepReadDiscoveryCacheTests: XCTestCase {
    private func item(_ provider: String, _ url: String?, title: String = "标题", topic: String? = nil) -> IOSDeepReadSource {
        var metadata = ["provider_id": provider, "rank": "1"]
        if let topic { metadata["topic_id"] = topic }
        return IOSDeepReadSource(id: UUID().uuidString, kind: .hotTopic, title: title, content: title, url: url,
                                 metadata: metadata, createdAt: 0)
    }

    private func task(_ id: String, _ status: IOSDeepReadTaskStatus, sources: [IOSDeepReadSource], at time: Int64,
                      primary: Bool = false, originalOnly: Bool = false) -> IOSDeepReadTask {
        var sources = sources
        if primary {
            sources[0].metadata[DeepReadCloseReader.roleKey] = DeepReadCloseReader.primaryRole
            if originalOnly { sources[0].metadata[DeepReadCloseReader.originalOnlyKey] = "true" }
        }
        // Generation appends search results; they must not affect identity.
        sources.append(IOSDeepReadSource(id: UUID().uuidString, kind: .searchResult, title: "搜索", content: "x",
                                         url: "https://example.com/s", metadata: [:], createdAt: 0))
        return IOSDeepReadTask(id: id, title: "t", status: status, templateId: "auto", sources: sources,
                               resultMarkdown: "", failureMessage: nil, createdAt: time, updatedAt: time,
                               completedAt: nil, retryCount: 0)
    }

    func testTappingSameHotItemOpensExistingReading() {
        let tasks = [task("a", .succeeded, sources: [item("zhihu", "https://z/1")], at: 1)]
        XCTAssertEqual(DeepReadDiscoveryCache.taskId(for: [item("zhihu", "https://z/1")], mode: nil, in: tasks), "a")
    }

    func testDifferentItemOrProviderDoesNotMatch() {
        let tasks = [task("a", .succeeded, sources: [item("zhihu", "https://z/1")], at: 1)]
        XCTAssertNil(DeepReadDiscoveryCache.taskId(for: [item("zhihu", "https://z/2")], mode: nil, in: tasks))
        XCTAssertNil(DeepReadDiscoveryCache.taskId(for: [item("weibo", "https://z/1")], mode: nil, in: tasks))
    }

    func testTopicMatchesAcrossRefreshesBySharedEntries() {
        // Topic ids hash ranks, so a refresh gives the same story a new id and a shifted source set.
        let old = [item("hn", "https://a", topic: "T1"), item("gh", "https://b", topic: "T1")]
        let tasks = [task("a", .succeeded, sources: old, at: 1)]
        let refreshed = [item("hn", "https://a", topic: "T9"), item("hf", "https://c", topic: "T9")]
        XCTAssertEqual(DeepReadDiscoveryCache.taskId(for: refreshed, mode: nil, in: tasks), "a")
        let unrelated = [item("hn", "https://x", topic: "T1"), item("hf", "https://y", topic: "T1")]
        XCTAssertNil(DeepReadDiscoveryCache.taskId(for: unrelated, mode: nil, in: tasks))
    }

    func testSingleItemDoesNotMatchTopicReadingContainingIt() {
        let tasks = [task("a", .succeeded, sources: [item("hn", "https://a", topic: "T1")], at: 1)]
        XCTAssertNil(DeepReadDiscoveryCache.taskId(for: [item("hn", "https://a")], mode: nil, in: tasks))
    }

    func testPrefersUsableReadingOverNewerFailureAndFallsBackToFailure() {
        let s = [item("zhihu", "https://z/1")]
        let both = [task("ok", .succeeded, sources: s, at: 1), task("bad", .failed, sources: s, at: 2)]
        XCTAssertEqual(DeepReadDiscoveryCache.taskId(for: s, mode: nil, in: both), "ok")
        let running = [task("old", .succeeded, sources: s, at: 1), task("run", .running, sources: s, at: 2)]
        XCTAssertEqual(DeepReadDiscoveryCache.taskId(for: s, mode: nil, in: running), "run")
        let failedOnly = [task("bad", .failed, sources: s, at: 2)]
        XCTAssertEqual(DeepReadDiscoveryCache.taskId(for: s, mode: nil, in: failedOnly), "bad")
    }

    func testExplicitModeOnlyMatchesSameMode() {
        let s = [item("hn", "https://a")]
        let tasks = [task("close", .succeeded, sources: s, at: 1, primary: true),
                     task("orig", .succeeded, sources: s, at: 2, primary: true, originalOnly: true)]
        XCTAssertEqual(DeepReadDiscoveryCache.taskId(for: s, mode: .closeReading, in: tasks), "close")
        XCTAssertEqual(DeepReadDiscoveryCache.taskId(for: s, mode: .originalOnly, in: tasks), "orig")
        XCTAssertNil(DeepReadDiscoveryCache.taskId(for: s, mode: .synthesis, in: tasks))
        XCTAssertEqual(DeepReadDiscoveryCache.taskId(for: s, mode: nil, in: tasks), "orig")
    }

    func testItemWithoutURLMatchesByTitle() {
        let tasks = [task("a", .succeeded, sources: [item("weibo", nil, title: "热搜词")], at: 1)]
        XCTAssertEqual(DeepReadDiscoveryCache.taskId(for: [item("weibo", nil, title: "热搜词")], mode: nil, in: tasks), "a")
        XCTAssertNil(DeepReadDiscoveryCache.taskId(for: [item("weibo", nil, title: "别的词")], mode: nil, in: tasks))
    }
}
