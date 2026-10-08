import Foundation

// MARK: - 搜索通用选项

/// Kotlin `SearchCommonOptions` 切片。
public struct SearchCommonOptions: Sendable, Equatable {
    public var resultSize: Int

    public init(resultSize: Int = 10) {
        self.resultSize = resultSize
    }
}

// MARK: - 搜索服务配置

/// Kotlin `SearchServiceOptions` 密封类的纯 Swift 切片：保留全部 17 个 case
/// 与字段默认值（设置页按 kind 保存字段字典，构造时缺失键回落 Kotlin 默认）。
/// class 层级保持 `service as? SearchServiceOptions.TavilyOptions` 的既有
/// 分发语法（IOSSearchExecutor 依赖）。
public class SearchServiceOptions: @unchecked Sendable {
    public let id: UUID

    public init(id: UUID = UUID()) {
        self.id = id
    }
}

public extension SearchServiceOptions {
    /// 免费多引擎聚合（Bing HTML 兜底）。
    final class BingLocalOptions: SearchServiceOptions, @unchecked Sendable {}

    final class ZhipuOptions: SearchServiceOptions, @unchecked Sendable {
        public let apiKey: String

        public init(id: UUID = UUID(), apiKey: String = "") {
            self.apiKey = apiKey
            super.init(id: id)
        }
    }

    final class TavilyOptions: SearchServiceOptions, @unchecked Sendable {
        public let apiKey: String
        public let depth: String

        public init(id: UUID = UUID(), apiKey: String = "", depth: String = "advanced") {
            self.apiKey = apiKey
            self.depth = depth
            super.init(id: id)
        }
    }

    final class ExaOptions: SearchServiceOptions, @unchecked Sendable {
        public let apiKey: String

        public init(id: UUID = UUID(), apiKey: String = "") {
            self.apiKey = apiKey
            super.init(id: id)
        }
    }

    final class SearXNGOptions: SearchServiceOptions, @unchecked Sendable {
        public let url: String
        public let engines: String
        public let language: String
        public let username: String
        public let password: String

        public init(
            id: UUID = UUID(),
            url: String = "",
            engines: String = "",
            language: String = "",
            username: String = "",
            password: String = ""
        ) {
            self.url = url
            self.engines = engines
            self.language = language
            self.username = username
            self.password = password
            super.init(id: id)
        }
    }

    final class LinkUpOptions: SearchServiceOptions, @unchecked Sendable {
        public let apiKey: String
        public let depth: String

        public init(id: UUID = UUID(), apiKey: String = "", depth: String = "standard") {
            self.apiKey = apiKey
            self.depth = depth
            super.init(id: id)
        }
    }

    final class BraveOptions: SearchServiceOptions, @unchecked Sendable {
        public let apiKey: String

        public init(id: UUID = UUID(), apiKey: String = "") {
            self.apiKey = apiKey
            super.init(id: id)
        }
    }

    final class SerperOptions: SearchServiceOptions, @unchecked Sendable {
        public let apiKey: String

        public init(id: UUID = UUID(), apiKey: String = "") {
            self.apiKey = apiKey
            super.init(id: id)
        }
    }

    final class SerpApiOptions: SearchServiceOptions, @unchecked Sendable {
        public let apiKey: String

        public init(id: UUID = UUID(), apiKey: String = "") {
            self.apiKey = apiKey
            super.init(id: id)
        }
    }

    final class MetasoOptions: SearchServiceOptions, @unchecked Sendable {
        public let apiKey: String

        public init(id: UUID = UUID(), apiKey: String = "") {
            self.apiKey = apiKey
            super.init(id: id)
        }
    }

    final class OllamaOptions: SearchServiceOptions, @unchecked Sendable {
        public let apiKey: String

        public init(id: UUID = UUID(), apiKey: String = "") {
            self.apiKey = apiKey
            super.init(id: id)
        }
    }

    final class PerplexityOptions: SearchServiceOptions, @unchecked Sendable {
        public let apiKey: String
        public let maxTokens: Int?
        public let maxTokensPerPage: Int?

        public init(
            id: UUID = UUID(),
            apiKey: String = "",
            maxTokens: Int? = nil,
            maxTokensPerPage: Int? = nil
        ) {
            self.apiKey = apiKey
            self.maxTokens = maxTokens
            self.maxTokensPerPage = maxTokensPerPage
            super.init(id: id)
        }
    }

    final class FirecrawlOptions: SearchServiceOptions, @unchecked Sendable {
        public let apiKey: String

        public init(id: UUID = UUID(), apiKey: String = "") {
            self.apiKey = apiKey
            super.init(id: id)
        }
    }

    final class JinaOptions: SearchServiceOptions, @unchecked Sendable {
        public let apiKey: String
        public let searchUrl: String
        public let scrapeUrl: String

        public init(
            id: UUID = UUID(),
            apiKey: String = "",
            searchUrl: String = "https://s.jina.ai/",
            scrapeUrl: String = "https://r.jina.ai/"
        ) {
            self.apiKey = apiKey
            self.searchUrl = searchUrl
            self.scrapeUrl = scrapeUrl
            super.init(id: id)
        }
    }

    final class BochaOptions: SearchServiceOptions, @unchecked Sendable {
        public let apiKey: String
        public let summary: Bool

        public init(id: UUID = UUID(), apiKey: String = "", summary: Bool = true) {
            self.apiKey = apiKey
            self.summary = summary
            super.init(id: id)
        }
    }

    final class AmberAgentSearchOptions: SearchServiceOptions, @unchecked Sendable {
        public let apiKey: String
        public let depth: String

        public init(id: UUID = UUID(), apiKey: String = "", depth: String = "standard") {
            self.apiKey = apiKey
            self.depth = depth
            super.init(id: id)
        }
    }

    final class GrokOptions: SearchServiceOptions, @unchecked Sendable {
        public let apiKey: String
        public let model: String
        public let customUrl: String
        public let systemPrompt: String

        public init(
            id: UUID = UUID(),
            apiKey: String = "",
            model: String = "grok-4-1-fast-non-reasoning",
            customUrl: String = "https://api.x.ai/v1/responses",
            systemPrompt: String = "You are a helpful search assistant. Search the web to find accurate and up-to-date information for the user's query. Provide a comprehensive answer with citations."
        ) {
            self.apiKey = apiKey
            self.model = model
            self.customUrl = customUrl
            self.systemPrompt = systemPrompt
            super.init(id: id)
        }
    }
}

// MARK: - Settings

/// Kotlin `Settings` 的纯 Swift 切片：只保留 DeepRead 读取的字段
/// （providers + 搜索相关 11 项）。DeepRead 从不持久化该结构——
/// 它由设置存储在内存中构造（原实现经由 kotlinx JSON 桥，纯 Swift 化后
/// 直接构造）。字段默认值对齐 Kotlin `Settings` 构造器。
public struct Settings: @unchecked Sendable {
    public var providers: [ProviderSetting]
    public var searchServices: [SearchServiceOptions]
    public var searchCommonOptions: SearchCommonOptions
    public var searchServiceSelected: Int
    public var searchEnabledServiceIds: [UUID]
    public var searchBuiltinDuckDuckGoEnabled: Bool
    public var searchBuiltinBingEnabled: Bool
    public var searchBuiltinJinaEnabled: Bool
    public var searchBuiltinWikipediaEnabled: Bool
    public var searchBuiltinHackerNewsEnabled: Bool
    public var searchGoogleWebViewFallbackEnabled: Bool

    public init(
        providers: [ProviderSetting] = [],
        searchServices: [SearchServiceOptions] = [SearchServiceOptions.BingLocalOptions()],
        searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
        searchServiceSelected: Int = 0,
        searchEnabledServiceIds: [UUID]? = nil,
        searchBuiltinDuckDuckGoEnabled: Bool = true,
        searchBuiltinBingEnabled: Bool = true,
        searchBuiltinJinaEnabled: Bool = true,
        searchBuiltinWikipediaEnabled: Bool = true,
        searchBuiltinHackerNewsEnabled: Bool = true,
        searchGoogleWebViewFallbackEnabled: Bool = true
    ) {
        self.providers = providers
        self.searchServices = searchServices
        self.searchCommonOptions = searchCommonOptions
        self.searchServiceSelected = searchServiceSelected
        // Kotlin：searchEnabledServiceIds 默认取第一个服务的 id。
        self.searchEnabledServiceIds = searchEnabledServiceIds ?? searchServices.prefix(1).map(\.id)
        self.searchBuiltinDuckDuckGoEnabled = searchBuiltinDuckDuckGoEnabled
        self.searchBuiltinBingEnabled = searchBuiltinBingEnabled
        self.searchBuiltinJinaEnabled = searchBuiltinJinaEnabled
        self.searchBuiltinWikipediaEnabled = searchBuiltinWikipediaEnabled
        self.searchBuiltinHackerNewsEnabled = searchBuiltinHackerNewsEnabled
        self.searchGoogleWebViewFallbackEnabled = searchGoogleWebViewFallbackEnabled
    }
}
