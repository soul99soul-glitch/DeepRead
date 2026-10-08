import Foundation
import Observation

@MainActor
@Observable
final class IOSHotListDashboardStore {
    static let shared = IOSHotListDashboardStore()

    private(set) var dashboard: IOSHotListDashboard
    /// Unfiltered provider data used as the cache source. The dashboard above
    /// is the current presentation projection and may remove rows for focus_only.
    private var rawDashboard: IOSHotListDashboard
    private(set) var isRefreshing = false
    private(set) var lastError: String?

    private let directory: URL
    private let fileURL: URL
    @ObservationIgnored private var projection: Projection?
    @ObservationIgnored private var rawTopicsLimit: Int?
    @ObservationIgnored private var discoveryRefreshTask: Task<Bool?, Never>?

    init(baseDirectory: URL? = nil, fileManager: FileManager = .default) {
        let root = baseDirectory
            ?? fileManager.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        directory = root.appendingPathComponent("deep_read", isDirectory: true)
        fileURL = directory.appendingPathComponent("hotlist_dashboard.json", isDirectory: false)
        let cachedDashboard = Self.load(from: fileURL, decoder: JSONDecoder(), fileManager: fileManager) ?? .empty
        rawDashboard = cachedDashboard
        dashboard = cachedDashboard
    }

    /// Configuration changes cancel and finish the preceding refresh before applying
    /// the new projection. Network restrictions do not block local presentation changes.
    /// Returns nil for cancellation, false for a blocked network gate, and true on completion.
    func refreshDiscovery(
        setting: TodayBoardSetting,
        force: Bool = true,
        limit: Int = 20,
        translate: IOSHotListTitleTranslate? = nil,
        canFetch: @escaping @MainActor () async -> Bool = { true }
    ) async -> Bool? {
        let previous = discoveryRefreshTask
        previous?.cancel()
        let work = Task<Bool?, Never> { @MainActor in
            if let previous { _ = await previous.value }
            guard !Task.isCancelled else { return nil }
            await applyCached(setting: setting, limit: limit)
            guard !Task.isCancelled else { return nil }
            let networkAllowed = await canFetch()
            guard !Task.isCancelled else { return nil }
            guard networkAllowed else { return false }
            await refresh(setting: setting, force: force, limit: limit, translate: translate)
            return Task.isCancelled ? nil : true
        }
        // Save every request, including one still waiting for its predecessor, so a
        // third configuration change cancels the second rather than racing it.
        discoveryRefreshTask = work
        return await withTaskCancellationHandler {
            await work.value
        } onCancel: {
            work.cancel()
        }
    }

    func refresh(
        setting: TodayBoardSetting,
        force: Bool = true,
        limit: Int = 20,
        translate: IOSHotListTitleTranslate? = nil
    ) async {
        guard !isRefreshing else { return }
        isRefreshing = true
        defer { isRefreshing = false }
        let enabledIds = IOSHotlistProviders.effectiveEnabledProviderIds(setting: setting)
        guard !enabledIds.isEmpty else {
            if rawDashboard == .empty, projection == Projection(setting: setting, limit: limit) {
                lastError = nil
                return
            }
            guard let result = await rebuild(
                raw: .empty, setting: setting, limit: limit,
                rebuildRawTopics: false, persistRaw: rawDashboard != .empty
            ), !Task.isCancelled else { return }
            publish(result, setting: setting, limit: limit)
            lastError = nil
            return
        }
        if !force, !shouldRefresh(setting: setting) {
            // Fresh enough to skip a re-fetch, but still fill in any untranslated titles
            // (the model may have become available after the last fetch, or a prior
            // translation was skipped — an enabled translator also runs on cached refresh).
            // applyTitleTranslations no-ops when nothing pends, so this spends an LLM call
            // only the first time new non-Chinese titles appear.
            let cached = rawDashboard.providers
            let prev = Dictionary(uniqueKeysWithValues: cached.map { ($0.providerId, $0) })
            let translated = await Self.applyTitleTranslations(to: cached, previous: prev, translate: translate)
            guard !Task.isCancelled else { return }
            let changed = translated != cached
            if !changed, projection == Projection(setting: setting, limit: limit) { return }
            var raw = rawDashboard
            raw.providers = translated
            guard let result = await rebuild(
                raw: raw, setting: setting, limit: limit,
                rebuildRawTopics: changed, persistRaw: changed
            ), !Task.isCancelled else { return }
            publish(result, setting: setting, limit: limit)
            return
        }

        lastError = nil

        let previous = Dictionary(uniqueKeysWithValues: rawDashboard.providers.map { ($0.providerId, $0) })
        let now = IOSHotListClock.currentEpochMs()
        let enabledProviders = IOSHotlistProviders.all.filter { enabledIds.contains($0.providerId) }

        // 并发抓取所有启用的来源。此前是顺序抓取,且某个源被取消/超时就 `return` 整批中断,
        // 导致后面的源(尤其新加的 NewsNow)永远抓不到、首页不显示。改成 TaskGroup 后单个源
        // 失败/为空只影响自己,互不拖累。
        var snapshots = await withTaskGroup(of: IOSHotListProviderSnapshot.self) { group in
            for provider in enabledProviders {
                group.addTask {
                    do {
                        let items = try await provider.fetch(limit: limit)
                        return IOSHotListProviderSnapshot(
                            providerId: provider.providerId,
                            providerName: provider.displayName,
                            items: items,
                            fetchedAt: now
                        )
                    } catch {
                        let cached = previous[provider.providerId]?.items ?? []
                        return IOSHotListProviderSnapshot(
                            providerId: provider.providerId,
                            providerName: provider.displayName,
                            items: cached,
                            fetchedAt: previous[provider.providerId]?.fetchedAt ?? now,
                            stale: !cached.isEmpty,
                            // 有缓存就不显示为错误(标 stale 即可);彻底拿不到才报错。
                            error: cached.isEmpty ? error.localizedDescription : nil
                        )
                    }
                }
            }
            var collected: [IOSHotListProviderSnapshot] = []
            for await snapshot in group { collected.append(snapshot) }
            return collected
        }
        // TaskGroup 完成顺序不定,还原成启用列表的稳定顺序。
        let orderIndex = Dictionary(uniqueKeysWithValues: enabledProviders.enumerated().map { ($1.providerId, $0) })
        snapshots.sort { (orderIndex[$0.providerId] ?? 0) < (orderIndex[$1.providerId] ?? 0) }

        // 整批已被取消(用户离开页面)→ 不用部分数据覆盖既有 dashboard。
        if Task.isCancelled { return }

        // The caller supplies a translator only when translation is enabled.
        // Reuse cached translations so only new titles need a model request.
        snapshots = await Self.applyTitleTranslations(to: snapshots, previous: previous, translate: translate)

        guard !Task.isCancelled else { return }
        let raw = IOSHotListDashboard(
            topics: [],
            providers: snapshots,
            lastUpdatedAt: snapshots.map(\.fetchedAt).max() ?? now,
            enabledSourceCount: enabledIds.count
        )
        guard let result = await rebuild(
            raw: raw, setting: setting, limit: limit,
            rebuildRawTopics: true, persistRaw: true
        ), !Task.isCancelled else { return }
        publish(result, setting: setting, limit: limit)
        if dashboard.hasErrors {
            lastError = dashboard.providers.compactMap(\.error).first
        }
    }

    /// Re-applies local source and interest filters without fetching or translating.
    func applyCached(setting: TodayBoardSetting, limit: Int = 20) async {
        guard projection != Projection(setting: setting, limit: limit) else { return }
        guard let result = await rebuild(
            raw: rawDashboard, setting: setting, limit: limit,
            rebuildRawTopics: false, persistRaw: false
        ), !Task.isCancelled else { return }
        publish(result, setting: setting, limit: limit)
    }

    /// Fills `displayTitle` with a Chinese translation for non-Chinese titles.
    /// First reuses any cached translation from the previous dashboard (keyed by
    /// raw title), then issues one batched LLM call for the remaining untranslated
    /// titles. No translator or no pending titles → snapshots returned unchanged.
    private static func applyTitleTranslations(
        to snapshots: [IOSHotListProviderSnapshot],
        previous: [String: IOSHotListProviderSnapshot],
        translate: IOSHotListTitleTranslate?
    ) async -> [IOSHotListProviderSnapshot] {
        // 1. Cache prior translations (raw title -> Chinese displayTitle).
        var cache: [String: String] = [:]
        for snap in previous.values {
            for item in snap.items {
                let dt = (item.displayTitle ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                if !dt.isEmpty, dt != item.title { cache[item.title] = dt }
            }
        }
        func applyCache(_ snaps: [IOSHotListProviderSnapshot]) -> [IOSHotListProviderSnapshot] {
            snaps.map { snap in
                var s = snap
                s.items = s.items.map { item in
                    var it = item
                    if (it.displayTitle ?? "").isEmpty, let cached = cache[it.title] { it.displayTitle = cached }
                    return it
                }
                return s
            }
        }
        var working = applyCache(snapshots)

        // 2. Translate the titles that are still untranslated and non-Chinese.
        let pending = working.flatMap(\.items)
            .filter { ($0.displayTitle ?? "").isEmpty && IOSHotListTitleTranslator.needsTranslation($0.title) }
            .map(\.title)
#if DEBUG
        NSLog("[AmberTranslate] apply pending=\(pending.count) translatorNil=\(translate == nil) cached=\(cache.count)")
#endif
        guard !pending.isEmpty, let translate else { return working }
        let translations = await translate(Array(Set(pending)))
        guard !translations.isEmpty else { return working }

        working = working.map { snap in
            var s = snap
            s.items = s.items.map { item in
                var it = item
                if (it.displayTitle ?? "").isEmpty, let zh = translations[it.title] { it.displayTitle = zh }
                return it
            }
            return s
        }
        return working
    }

    static func topic(from provider: IOSHotListProviderSnapshot, item: IOSHotlistItem) -> IOSHotTopic {
        let source = IOSHotTopicSource(
            providerId: provider.providerId,
            providerName: provider.providerName,
            rank: item.rank,
            title: item.title,
            displayTitle: item.displayTitle,
            url: item.url,
            heat: item.heat ?? item.score.map(String.init),
            fetchedAt: item.fetchedAt
        )
        return IOSHotListAggregator.aggregate(
            providerSnapshots: [
                IOSHotListProviderSnapshot(
                    providerId: provider.providerId,
                    providerName: provider.providerName,
                    items: [item],
                    fetchedAt: provider.fetchedAt,
                    stale: provider.stale,
                    error: provider.error
                )
            ],
            limit: 1
        ).first ?? IOSHotTopic(
            id: UUID().uuidString,
            title: source.presentationTitle,
            sources: [source],
            sourceCount: 1,
            bestRank: source.rank,
            latestFetchedAt: source.fetchedAt
        )
    }

    func shouldRefresh(setting: TodayBoardSetting) -> Bool {
        guard rawDashboard.hasContent, rawDashboard.lastUpdatedAt > 0 else { return true }
        // 启用来源集合变了(新开/关了源,比如刚加的 NewsNow)→ 立刻刷新,否则缓存里没有
        // 这些源,首页就一直不显示它们,直到刷新间隔过去。
        let enabledIds = IOSHotlistProviders.effectiveEnabledProviderIds(setting: setting)
        let fetchedIds = Set(rawDashboard.providers.map(\.providerId))
        if enabledIds != fetchedIds { return true }
        let minutes = max(Int(setting.hotListRefreshIntervalMinutes), 15)
        let gapMs = Int64(minutes) * 60_000
        return IOSHotListClock.currentEpochMs() - rawDashboard.lastUpdatedAt >= gapMs
    }

    private struct Projection: Equatable, Sendable {
        let enabledIds: Set<String>
        let translate: Bool
        let keywords: [String]
        let filterMode: String
        let limit: Int

        init(setting: TodayBoardSetting, limit: Int) {
            enabledIds = IOSHotlistProviders.effectiveEnabledProviderIds(setting: setting)
            translate = setting.hotListTranslateToChinese
            keywords = setting.hotListFocusKeywords
            filterMode = setting.hotListFilterMode.wireName
            self.limit = limit
        }
    }

    private struct BuildResult: Sendable {
        let raw: IOSHotListDashboard
        let visible: IOSHotListDashboard
        let rawTopicsLimit: Int?
    }

    /// Aggregation, projection and disk work share one background step. Only
    /// the completed value crosses back to the observable main-actor store.
    private func rebuild(
        raw: IOSHotListDashboard,
        setting: TodayBoardSetting,
        limit: Int,
        rebuildRawTopics: Bool,
        persistRaw: Bool
    ) async -> BuildResult? {
        let projection = Projection(setting: setting, limit: limit)
        let rawTopicsLimit = rebuildRawTopics ? limit : self.rawTopicsLimit
        let directory = directory
        let fileURL = fileURL
        let build = Task.detached(priority: .userInitiated) {
            try Task.checkCancellation()
            var raw = raw
            if rebuildRawTopics {
                raw.topics = IOSHotListAggregator.aggregate(providerSnapshots: raw.providers, limit: limit)
            }
            let visible = Self.filteredDashboard(raw, projection: projection, rawTopicsLimit: rawTopicsLimit)
            try Task.checkCancellation()
            if persistRaw {
                do {
                    let data = try JSONEncoder().encode(raw)
                    try Task.checkCancellation()
                    try FileManager().createDirectory(at: directory, withIntermediateDirectories: true)
                    try data.write(to: fileURL, options: [.atomic])
                } catch is CancellationError {
                    throw CancellationError()
                } catch {
                    print("[IOSHotListDashboardStore] persist failed: \(error.localizedDescription)")
                }
            }
            return BuildResult(raw: raw, visible: visible, rawTopicsLimit: rawTopicsLimit)
        }
        return try? await withTaskCancellationHandler {
            try await build.value
        } onCancel: {
            build.cancel()
        }
    }

    private func publish(_ result: BuildResult, setting: TodayBoardSetting, limit: Int) {
        rawDashboard = result.raw
        dashboard = result.visible
        rawTopicsLimit = result.rawTopicsLimit
        projection = Projection(setting: setting, limit: limit)
    }

    private nonisolated static func filteredDashboard(
        _ raw: IOSHotListDashboard,
        projection: Projection,
        rawTopicsLimit: Int?
    ) -> IOSHotListDashboard {
        let providers = raw.providers
            .filter { projection.enabledIds.contains($0.providerId) }
            .map { provider in
                guard !projection.translate else { return provider }
                var projected = provider
                projected.items = projected.items.map { item in
                    var item = item
                    item.displayTitle = nil
                    return item
                }
                return projected
            }
        // The common all-sources projection already has exactly these topics.
        let topics = providers == raw.providers && rawTopicsLimit == projection.limit
            ? raw.topics
            : IOSHotListAggregator.aggregate(providerSnapshots: providers, limit: projection.limit)
        let visible = IOSHotListDashboard(
            topics: topics,
            providers: providers,
            lastUpdatedAt: raw.lastUpdatedAt,
            enabledSourceCount: projection.enabledIds.count
        )
        return IOSHotListAggregator.applyInterestFilter(
            dashboard: visible,
            keywords: projection.keywords,
            modeWireName: projection.filterMode
        )
    }

    private static func load(from url: URL, decoder: JSONDecoder, fileManager: FileManager) -> IOSHotListDashboard? {
        guard fileManager.fileExists(atPath: url.path),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? decoder.decode(IOSHotListDashboard.self, from: data)
    }
}
