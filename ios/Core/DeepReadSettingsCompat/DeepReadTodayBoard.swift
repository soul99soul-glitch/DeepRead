import Foundation

// MARK: - 热榜来源常量

/// Kotlin `HotListProviderIds` 切片（Swift 侧实际只消费默认启用集与
/// 来源标识字符串）。
public enum HotListProviderIds {
    public static let weibo = "weibo"
    public static let zhihu = "zhihu"
    public static let bilibili = "bilibili"
    public static let hackerNews = "hacker_news"
    public static let arxivAI = "arxiv_ai"
    public static let infoqAI = "infoq_ai"
    public static let huggingfacePapers = "huggingface_papers"
    public static let githubTrendingAI = "github_trending_ai"
    public static let douyin = "douyin"
    public static let baidu = "baidu"
    public static let toutiao = "toutiao"
    public static let kr36 = "36kr"

    /// Kotlin `DEFAULT_ENABLED`。
    public static let defaultEnabled: Set<String> = [bilibili, hackerNews]
}

// MARK: - 过滤模式

/// Kotlin `TodayBoardHotListFilterMode` 切片。
public enum TodayBoardHotListFilterMode: String, Sendable, Equatable {
    case all
    case focusFirst = "focus_first"
    case focusOnly = "focus_only"

    public var wireName: String { rawValue }

    /// Kotlin `fromWireName`：未识别值回落 `.focusFirst`。
    public init(fromWireName raw: String?) {
        self = TodayBoardHotListFilterMode(rawValue: raw ?? "") ?? .focusFirst
    }
}

// MARK: - TodayBoard 设置切片

/// Kotlin `TodayBoardSetting` 的纯 Swift 切片：DeepRead 只消费 6 个
/// 热榜字段（enabledSources/triggerHours/density 等其余字段 Swift 层
/// 从不读取，直接省略）。默认值对齐 Kotlin 构造器。
public struct TodayBoardSetting: Sendable, Equatable {
    public var hotListRefreshIntervalMinutes: Int
    public var hotListWifiOnly: Bool
    public var hotListEnabledSources: Set<String>
    public var hotListFocusKeywords: [String]
    public var hotListFilterMode: TodayBoardHotListFilterMode
    public var hotListTranslateToChinese: Bool

    public init(
        hotListRefreshIntervalMinutes: Int = 60,
        hotListWifiOnly: Bool = false,
        hotListEnabledSources: Set<String> = HotListProviderIds.defaultEnabled,
        hotListFocusKeywords: [String] = DeepReadSettingsDefaults.defaultHotListFocusKeywords,
        hotListFilterMode: TodayBoardHotListFilterMode = .focusFirst,
        hotListTranslateToChinese: Bool = false
    ) {
        self.hotListRefreshIntervalMinutes = hotListRefreshIntervalMinutes
        self.hotListWifiOnly = hotListWifiOnly
        self.hotListEnabledSources = hotListEnabledSources
        self.hotListFocusKeywords = hotListFocusKeywords
        self.hotListFilterMode = hotListFilterMode
        self.hotListTranslateToChinese = hotListTranslateToChinese
    }
}

/// Kotlin `IosSettingsDefaults` 在 DeepRead 中的等价物：原实现为整棵
/// Settings 种子树，DeepRead 实际只消费 todayBoard 的热榜默认值。
public enum DeepReadSettingsDefaults {
    /// Kotlin `DEFAULT_HOT_LIST_FOCUS_KEYWORDS` 直译。
    public static let defaultHotListFocusKeywords: [String] = [
        "AI",
        "人工智能",
        "大模型",
        "LLM",
        "Agent",
        "机器人",
        "具身智能",
        "自动驾驶",
        "数码",
        "3C",
        "智能硬件",
        "芯片",
        "半导体",
        "OpenAI",
        "Claude",
        "DeepSeek",
        "Gemini",
        "NVIDIA",
        "小米",
        "华为",
        "特斯拉",
    ]
}
