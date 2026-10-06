import Foundation
import Observation

@Observable @MainActor
final class DeepReadDiscoveryPreferences {
    static let shared = DeepReadDiscoveryPreferences()
    struct Configuration: Codable, Hashable {
        var enabledSources = IOSHotlistProviders.iOSDefaultProviderIds.intersection(IOSHotlistProviders.supportedProviderIds)
        var keywords = ""
        var filterMode = "all"
        var refreshMinutes = 30
        var wifiOnly = false
        var translateToChinese = false
    }
    var configuration: Configuration {
        didSet {
            guard configuration != oldValue else { return }
            if let data = try? JSONEncoder().encode(configuration) {
                defaults.set(data, forKey: Self.key)
            }
        }
    }
    @ObservationIgnored private let defaults: UserDefaults
    private static let key = "deepread.discovery.v1"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        configuration = defaults.data(forKey: Self.key)
            .flatMap { try? JSONDecoder().decode(Configuration.self, from: $0) } ?? Configuration()
    }

    /// Kotlin 版经 IosSettingsDefaults 取种子再回填；DeepRead 只消费 6 个
    /// 热榜字段，直接用本地 `TodayBoardSetting` 切片构造（默认值内建）。
    var boardSetting: TodayBoardSetting {
        TodayBoardSetting(
            hotListRefreshIntervalMinutes: configuration.refreshMinutes,
            hotListWifiOnly: configuration.wifiOnly,
            hotListEnabledSources: configuration.enabledSources,
            hotListFocusKeywords: configuration.keywords.components(separatedBy: CharacterSet(charactersIn: "、,，\n"))
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty },
            hotListFilterMode: TodayBoardHotListFilterMode(fromWireName: configuration.filterMode),
            hotListTranslateToChinese: configuration.translateToChinese
        )
    }
}
