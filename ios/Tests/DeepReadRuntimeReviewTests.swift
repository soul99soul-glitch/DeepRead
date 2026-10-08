import XCTest
@testable import AmberDeepRead

@MainActor
final class DeepReadRuntimeReviewTests: XCTestCase {
    private struct EmptyCredentials: DeepReadCredentialStorage {
        func read(account: String) throws -> String? { nil }
        func write(_ value: String, account: String) throws {}
    }

    func testComparisonRetryPreservesCompletedGuideAndOnlyRequestsComparison() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = IOSDeepReadStore(baseDirectory: root)
        let settings = DeepReadSettingsStore(defaults: UserDefaults(suiteName: UUID().uuidString)!, credentials: EmptyCredentials())
        var model = DeepReadModelConfiguration()
        model.apiKey = "test-key"
        settings.models = [model]
        settings.selectedModelID = model.id
        XCTAssertTrue(settings.save())
        let guide = #"{"genre":"general","title":"阅读标题","guide":"已经完成的导读。","body_start":1,"body_end":2,"notes":[{"paragraph":1,"kind":"context","title":"已有批注","body":"已完成的背景解释"}]}"#
        let comparison = #"{"others":[{"source":1,"stance":"add","summary":"另一报道补充事实"}],"notes":[{"paragraph":2,"kind":"add","sources":[1],"title":"新补充","body":"对照后的补充内容"}]}"#
        let provider = DeepReadPipelineTests.StageProvider([guide, "not json", comparison])
        let report = IOSDeepReadSource(kind: .searchResult, title: "另一报道", content: "真实的另一报道正文", url: "https://example.org/report", metadata: ["scrape_status": "ok"])
        let runtime = DeepReadRuntime(settings: settings, store: store, provider: provider,
            enrichSources: { sources, _, _ in sources },
            searchReports: { _, _ in [report] }, continuedProcessing: nil,
            beginBackgroundTask: { _, _ in .invalid }, endBackgroundTask: { _ in })
        let id = try runtime.create(title: "阅读标题", sources: [
            .init(kind: .manualText, title: "原文", content: "第一段原文。\n第二段原文。")
        ], primaryIndex: 0)
        await waitForFinish(runtime)
        let before = try XCTUnwrap(store.task(id: id))
        XCTAssertEqual(before.missingSections, [DeepReadCloseReader.compareMissingSection])
        let originalReading = try XCTUnwrap(DeepReadCloseReading.decode(before.structuredJSON))

        try runtime.retry(taskId: id)
        await waitForFinish(runtime)
        let after = try XCTUnwrap(store.task(id: id))
        let reading = try XCTUnwrap(DeepReadCloseReading.decode(after.structuredJSON))
        XCTAssertEqual(provider.callCount, 3, "only the missing comparison should be requested")
        XCTAssertEqual(reading.guide, originalReading.guide)
        XCTAssertEqual(reading.bodyStart, originalReading.bodyStart)
        XCTAssertEqual(reading.bodyEnd, originalReading.bodyEnd)
        XCTAssertEqual(reading.paragraphs, originalReading.paragraphs)
        XCTAssertTrue(reading.notes.contains { $0.title == "已有批注" })
        XCTAssertEqual(reading.others?.first?.summary, "另一报道补充事实")
        XCTAssertTrue(reading.notes.contains { $0.title == "新补充" })
        XCTAssertNil(after.missingSections)
        XCTAssertEqual(IOSDeepReadStore(baseDirectory: root).task(id: id)?.structuredJSON, after.structuredJSON)
    }

    private func waitForFinish(_ runtime: DeepReadRuntime) async {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline, !runtime.activeTaskIds.isEmpty { try? await Task.sleep(for: .milliseconds(10)) }
        XCTAssertTrue(runtime.activeTaskIds.isEmpty)
    }
}
