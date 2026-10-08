import BackgroundTasks
import Foundation
import OSLog

/// Owns only system execution time. The runtime remains the owner of generation.
@MainActor
final class DeepReadBackgroundExecution {
    static let shared = DeepReadBackgroundExecution()
    static let identifierPrefix = "app.amber.deepread.generation."
    private static let stageWorkUnits: Int64 = 10_000
    private static let logger = Logger(subsystem: "app.amber.deepread", category: "BackgroundGeneration")

    /// The lock also allows an expiration handler to finish synchronously off the main actor.
    final class SystemTask: @unchecked Sendable {
        private let lock = NSLock()
        private var finished = false
        private let completion: (Bool) -> Void
        let setExpiration: (@escaping @Sendable () -> Void) -> Void
        let update: (String, String, Int64, Int64) -> Void

        init(setExpiration: @escaping (@escaping @Sendable () -> Void) -> Void,
             update: @escaping (String, String, Int64, Int64) -> Void,
             completion: @escaping (Bool) -> Void) {
            self.setExpiration = setExpiration
            self.update = update
            self.completion = completion
        }

        convenience init(_ task: BGContinuedProcessingTask) {
            self.init(setExpiration: { task.expirationHandler = $0 }, update: { title, label, completed, total in
                task.progress.totalUnitCount = total
                task.progress.completedUnitCount = completed
                task.updateTitle(title, subtitle: label)
            }, completion: {
                task.expirationHandler = nil
                task.setTaskCompleted(success: $0)
            })
        }

        func complete(success: Bool) {
            lock.lock()
            guard !finished else { lock.unlock(); return }
            finished = true
            lock.unlock()
            completion(success)
        }

        var isFinished: Bool {
            lock.lock()
            defer { lock.unlock() }
            return finished
        }
    }

    @MainActor
    struct Scheduler {
        var register: (String, @escaping @Sendable (SystemTask) -> Void) -> Bool
        var submit: (String, String) throws -> Void
        var cancel: (String) -> Void

        static let live = Scheduler(register: { identifier, launch in
            BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: .main) { task in
                guard let task = task as? BGContinuedProcessingTask else {
                    task.setTaskCompleted(success: false)
                    return
                }
                launch(SystemTask(task))
            }
        }, submit: { identifier, title in
            let request = BGContinuedProcessingTaskRequest(identifier: identifier, title: title, subtitle: "准备生成")
            request.strategy = .queue
            try BGTaskScheduler.shared.submit(request)
        }, cancel: { BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: $0) })
    }

    private struct Entry {
        let title: String
        let onReady: @MainActor () -> Void
        let onExpiration: @MainActor () -> Void
        var task: SystemTask?
        var label = "准备生成"
        var completed: Int64 = 0
        var total: Int64 = 1
        var receivedCharacters: Int64 = 0
        var lastStreamReport: Date?
    }

    private let scheduler: Scheduler
    private var entries: [UUID: Entry] = [:]

    init(scheduler: Scheduler = .live) { self.scheduler = scheduler }

    @discardableResult
    func begin(id: UUID, title: String, onReady: @escaping @MainActor () -> Void,
               onExpiration: @escaping @MainActor () -> Void) -> Bool {
        entries[id] = Entry(title: title, onReady: onReady, onExpiration: onExpiration)
        let identifier = Self.identifierPrefix + id.uuidString
        // Concrete, unique registrations avoid duplicate registration across retries.
        guard scheduler.register(identifier, { [weak self] task in
            task.setExpiration { [weak self] in
                task.complete(success: false)
                Task { @MainActor in self?.expire(id: id) }
            }
            Task { @MainActor in
                guard let self else { task.complete(success: false); return }
                self.adopt(task, id: id)
            }
        }) else {
            entries.removeValue(forKey: id)
            Self.logger.error("Continued task registration rejected")
            return false
        }
        do {
            try scheduler.submit(identifier, title)
            Self.logger.info("Continued task submitted: \(id.uuidString, privacy: .public)")
            return true
        } catch {
            entries.removeValue(forKey: id)
            Self.logger.error("Continued task submission failed: \((error as NSError).code)")
            return false
        }
    }

    func update(id: UUID, label: String, completed: Int? = nil, total: Int? = nil) {
        guard var entry = entries[id] else { return }
        entry.label = label
        if let total { entry.total = Int64(max(1, total)) }
        if let completed { entry.completed = Int64(max(0, completed)) }
        entries[id] = entry
        report(entry)
    }

    func receive(id: UUID, characters: Int) {
        guard characters > 0, var entry = entries[id] else { return }
        entry.receivedCharacters += Int64(characters)
        // Coalesce fast token streams; every report still comes from real output.
        let now = Date()
        let shouldReport = entry.lastStreamReport.map { now.timeIntervalSince($0) >= 1 } ?? true
        if shouldReport { entry.lastStreamReport = now }
        entries[id] = entry
        if shouldReport { report(entry) }
    }

    private func report(_ entry: Entry) {
        // Indeterminate Progress was expired on-device despite regular real output.
        // Keep unfinished stages as estimated work, and add each received character
        // to both counts so the fraction advances without claiming a known output length.
        let completed = entry.completed * Self.stageWorkUnits + entry.receivedCharacters
        let total = entry.total * Self.stageWorkUnits + entry.receivedCharacters
        let label = entry.receivedCharacters > 0
            ? "\(entry.label) · 已生成 \(entry.receivedCharacters) 字符" : entry.label
        entry.task?.update(entry.title, label, completed, total)
    }

    func finish(id: UUID, success: Bool) {
        guard let entry = entries.removeValue(forKey: id) else { return }
        scheduler.cancel(Self.identifierPrefix + id.uuidString)
        if success {
            let total = entry.total * Self.stageWorkUnits + entry.receivedCharacters
            entry.task?.update(entry.title, "生成完成", total, total)
        }
        entry.task?.complete(success: success)
    }

    private func adopt(_ task: SystemTask, id: UUID) {
        guard var entry = entries[id] else { task.complete(success: false); return }
        guard !task.isFinished else { expire(id: id); return }
        entry.task = task
        entries[id] = entry
        report(entry)
        Self.logger.info("Continued task adopted: \(id.uuidString, privacy: .public)")
        entry.onReady()
    }

    private func expire(id: UUID) {
        guard let entry = entries[id] else { return }
        // The runtime persists its terminal state, then calls finish(false).
        entry.onExpiration()
    }
}
