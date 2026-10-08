import Foundation

// MARK: - OpenAI 认证与品牌

/// Kotlin `OpenAIAuthMode` 切片。DeepRead 设置界面只会产生 `.apiKey`；
/// 其余档位保留以完整对齐 Kotlin 枚举（编码名与 Kotlin SerialName 一致）。
public enum OpenAIAuthMode: String, Sendable, Codable {
    case apiKey = "api_key"
    case codexOAuth = "codex_oauth"
    case zhipuCodingPlan = "zhipu_coding_plan"
    case kimiCodingPlan = "kimi_coding_plan"
    case mimoCodingPlan = "mimo_coding_plan"
    case minimaxTokenPlan = "minimax_token_plan"

    /// Kotlin `isCodingPlan()`。
    public var isCodingPlan: Bool {
        switch self {
        case .zhipuCodingPlan, .kimiCodingPlan, .mimoCodingPlan, .minimaxTokenPlan: true
        case .apiKey, .codexOAuth: false
        }
    }
}

/// Kotlin `OpenAIBrand` 切片。DeepRead 设置界面只会产生 `.generic`。
public enum OpenAIBrand: String, Sendable, Codable {
    case generic, openai, deepseek, zhipu, kimi, mimo, minimax
}

// MARK: - ProviderSetting 具体 case

public extension ProviderSetting {
    /// OpenAI 兼容 API（Chat Completions 或 Responses）。
    final class OpenAI: ProviderSetting, @unchecked Sendable {
        public var apiKey: String
        public var baseUrl: String
        public var chatCompletionsPath: String
        public var useResponseApi: Bool
        public var authMode: OpenAIAuthMode
        public var brand: OpenAIBrand

        public init(
            id: UUID = UUID(),
            enabled: Bool = true,
            name: String = "OpenAI",
            models: [Model] = [],
            balanceOption: BalanceOption = BalanceOption(),
            builtIn: Bool = false,
            descriptionText: String? = nil,
            shortDescriptionText: String? = nil,
            apiKey: String = "",
            baseUrl: String = "https://api.openai.com/v1",
            chatCompletionsPath: String = "/chat/completions",
            useResponseApi: Bool = false,
            authMode: OpenAIAuthMode = .apiKey,
            brand: OpenAIBrand = .generic
        ) {
            self.apiKey = apiKey
            self.baseUrl = baseUrl
            self.chatCompletionsPath = chatCompletionsPath
            self.useResponseApi = useResponseApi
            self.authMode = authMode
            self.brand = brand
            super.init(
                id: id, enabled: enabled, name: name, models: models,
                balanceOption: balanceOption, builtIn: builtIn,
                descriptionText: descriptionText, shortDescriptionText: shortDescriptionText
            )
        }
    }

    /// Claude 原生 /messages API。
    final class Claude: ProviderSetting, @unchecked Sendable {
        public var apiKey: String
        public var baseUrl: String
        public var promptCaching: Bool

        public init(
            id: UUID = UUID(),
            enabled: Bool = true,
            name: String = "Claude",
            models: [Model] = [],
            balanceOption: BalanceOption = BalanceOption(),
            builtIn: Bool = false,
            descriptionText: String? = nil,
            shortDescriptionText: String? = nil,
            apiKey: String = "",
            baseUrl: String = "https://api.anthropic.com/v1",
            promptCaching: Bool = false
        ) {
            self.apiKey = apiKey
            self.baseUrl = baseUrl
            self.promptCaching = promptCaching
            super.init(
                id: id, enabled: enabled, name: name, models: models,
                balanceOption: balanceOption, builtIn: builtIn,
                descriptionText: descriptionText, shortDescriptionText: shortDescriptionText
            )
        }
    }
}

public extension ProviderSetting {
    /// Kotlin `ProviderSetting.hasUsableAuth()` 的 OpenAI/Claude 切片：
    /// OAuth 模式由平台层把关，这里只认 apiKey 非空。
    var hasUsableAuth: Bool {
        guard enabled else { return false }
        switch self {
        case let openAI as OpenAI:
            let trimmedKey = openAI.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
            return openAI.authMode == .codexOAuth || !trimmedKey.isEmpty
        case let claude as Claude:
            return !claude.apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        default:
            return false
        }
    }
}

// MARK: - 文本生成协议

/// 文本生成的最小 Provider 协议（原定义在 DeepReadProviderAdapter.swift，
/// 随共享层迁移至此）。
public protocol IOSAgentTextProvider: Sendable {
    func generateText(providerSetting: ProviderSetting, messages: [UIMessage],
                      params: TextGenerationParams) async throws -> MessageChunk
}
