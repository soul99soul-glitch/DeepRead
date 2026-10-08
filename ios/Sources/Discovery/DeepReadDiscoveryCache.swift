import Foundation

/// Finds an earlier reading of a Discovery entry, so tapping it again opens that reading instead of
/// generating a new one. Identity comes from the hot-list entries (provider plus link, or title) a
/// reading was started from. A single item must match exactly; a multi-source topic matches a topic
/// reading sharing any entry, because topic ids hash ranks and change on every hot-list refresh.
enum DeepReadDiscoveryCache {
    enum Mode { case closeReading, originalOnly, synthesis }

    /// `mode == nil` is a plain tap and accepts a reading in any mode; an explicit mode from the
    /// long-press menu only reuses a reading made the same way. A usable or in-progress reading wins
    /// over a newer failure; a failed one is still returned so its page (with retry) opens.
    static func taskId(for request: [IOSDeepReadSource], mode: Mode?, in tasks: [IOSDeepReadTask]) -> String? {
        guard let wanted = identity(of: request) else { return nil }
        let matches = tasks.filter { task in
            guard let found = identity(of: task.sources), found.isTopic == wanted.isTopic,
                  wanted.isTopic ? !found.entries.isDisjoint(with: wanted.entries) : found.entries == wanted.entries
            else { return false }
            return mode == nil || self.mode(of: task) == mode
        }
        func latest(_ list: [IOSDeepReadTask]) -> IOSDeepReadTask? { list.max { $0.createdAt < $1.createdAt } }
        let usable = matches.filter { $0.status != .failed && $0.status != .unsupported }
        return (latest(usable) ?? latest(matches))?.id
    }

    private static func identity(of sources: [IOSDeepReadSource]) -> (isTopic: Bool, entries: Set<String>)? {
        let hot = sources.filter { $0.kind == .hotTopic && $0.metadata["provider_id"] != nil }
        guard !hot.isEmpty else { return nil }
        let isTopic = hot.contains { !($0.metadata["topic_id"] ?? "").isEmpty }
        guard isTopic || hot.count == 1 else { return nil }
        return (isTopic, Set(hot.map { "\($0.metadata["provider_id"]!)|\($0.url ?? $0.title)" }))
    }

    private static func mode(of task: IOSDeepReadTask) -> Mode {
        guard let primary = task.sources.first(where: DeepReadCloseReader.isPrimary) else { return .synthesis }
        return primary.metadata[DeepReadCloseReader.originalOnlyKey] == "true" ? .originalOnly : .closeReading
    }
}
