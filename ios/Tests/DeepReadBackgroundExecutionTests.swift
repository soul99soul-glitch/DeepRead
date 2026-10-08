import XCTest
@testable import AmberDeepRead

@MainActor
final class DeepReadBackgroundExecutionTests: XCTestCase {
    func testStreamingOutputReportsRealWorkWhileSingleStageIsStillRunning() async throws {
        var launch: (@Sendable (DeepReadBackgroundExecution.SystemTask) -> Void)?
        var updates: [(Int64, Int64)] = []
        let background = DeepReadBackgroundExecution(scheduler: .init(
            register: { _, callback in launch = callback; return true },
            submit: { _, _ in }, cancel: { _ in }))
        let id = UUID()
        background.begin(id: id, title: "阅读", onReady: {}, onExpiration: {})
        background.update(id: id, label: "正在生成", completed: 2, total: 3)
        let task = DeepReadBackgroundExecution.SystemTask(setExpiration: { _ in },
            update: { _, _, completed, total in updates.append((completed, total)) }, completion: { _ in })
        try XCTUnwrap(launch)(task)
        try await Task.sleep(for: .milliseconds(30))
        background.receive(id: id, characters: 42)
        XCTAssertEqual(updates.last?.0, 20_042)
        XCTAssertEqual(updates.last?.1, 30_042, "system progress must stay determinate while real output advances")
        XCTAssertGreaterThan(Double(try XCTUnwrap(updates.last?.0)) / Double(try XCTUnwrap(updates.last?.1)), 2.0 / 3.0)
        background.receive(id: id, characters: 8)
        background.update(id: id, label: "保存结果", completed: 3)
        XCTAssertEqual(updates.last?.0, 30_050)
        background.finish(id: id, success: true)
        XCTAssertEqual(updates.last?.0, 30_050)
        XCTAssertEqual(updates.last?.1, 30_050)
        background.receive(id: id, characters: 100)
        XCTAssertEqual(updates.last?.0, 30_050, "late output must not revive a finished system task")
    }

    func testCompletionCancelsQueuedRequestAndRejectsLateLaunch() async throws {
        var launch: (@Sendable (DeepReadBackgroundExecution.SystemTask) -> Void)?
        var submitted: [String] = []
        var cancelled: [String] = []
        var completions: [Bool] = []
        let background = DeepReadBackgroundExecution(scheduler: .init(
            register: { _, callback in launch = callback; return true },
            submit: { identifier, _ in submitted.append(identifier) },
            cancel: { cancelled.append($0) }))
        let id = UUID()
        XCTAssertTrue(background.begin(id: id, title: "阅读", onReady: { XCTFail("late launch must not adopt") },
                                       onExpiration: { XCTFail("finished run must not expire") }))
        background.finish(id: id, success: true)
        let task = DeepReadBackgroundExecution.SystemTask(setExpiration: { _ in },
            update: { _, _, _, _ in }, completion: { completions.append($0) })
        try XCTUnwrap(launch)(task)
        try await Task.sleep(for: .milliseconds(30))
        XCTAssertEqual(cancelled, submitted)
        XCTAssertEqual(submitted, [DeepReadBackgroundExecution.identifierPrefix + id.uuidString])
        XCTAssertEqual(completions, [false])
    }

    func testExpirationBeforeAdoptionCannotAcquireExecutionTime() async throws {
        var launch: (@Sendable (DeepReadBackgroundExecution.SystemTask) -> Void)?
        var completions: [Bool] = []
        var expirationCount = 0
        let id = UUID()
        let background = DeepReadBackgroundExecution(scheduler: .init(
            register: { _, callback in launch = callback; return true },
            submit: { _, _ in }, cancel: { _ in }))
        XCTAssertTrue(background.begin(id: id, title: "阅读", onReady: { XCTFail("expired task must not adopt") },
            onExpiration: {
                expirationCount += 1
                background.finish(id: id, success: false)
            }))
        let task = DeepReadBackgroundExecution.SystemTask(setExpiration: { handler in handler() },
            update: { _, _, _, _ in XCTFail("expired task must not update progress") },
            completion: { completions.append($0) })
        try XCTUnwrap(launch)(task)
        try await Task.sleep(for: .milliseconds(30))
        XCTAssertEqual(expirationCount, 1)
        XCTAssertEqual(completions, [false])
    }

    func testRejectedSubmissionDoesNotKeepAnExecutionOwner() async throws {
        var launch: (@Sendable (DeepReadBackgroundExecution.SystemTask) -> Void)?
        var completions: [Bool] = []
        let background = DeepReadBackgroundExecution(scheduler: .init(
            register: { _, callback in launch = callback; return true },
            submit: { _, _ in throw NSError(domain: "BGTaskSchedulerErrorDomain", code: 3) },
            cancel: { _ in XCTFail("rejected submission has no pending request") }))
        let id = UUID()
        XCTAssertFalse(background.begin(id: id, title: "阅读", onReady: { XCTFail("rejected task must not adopt") },
                                        onExpiration: { XCTFail("rejected task has no owner") }))
        let task = DeepReadBackgroundExecution.SystemTask(setExpiration: { _ in },
            update: { _, _, _, _ in }, completion: { completions.append($0) })
        try XCTUnwrap(launch)(task)
        try await Task.sleep(for: .milliseconds(30))
        XCTAssertEqual(completions, [false])
    }
}
