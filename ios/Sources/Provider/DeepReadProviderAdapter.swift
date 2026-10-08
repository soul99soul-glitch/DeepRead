import Foundation

/// 纯 Swift 双协议适配器（原 OpenAIKmpProviderAdapter 委托 Shared 的 KMP
/// Provider；纯 Swift 化后直接分发给 DeepReadOpenAIProvider /
/// DeepReadClaudeProvider，认证门控行为不变）。
public struct DeepReadAIProviderAdapter: IOSAgentTextProvider {
    private let openAI: DeepReadOpenAIProvider
    private let claude: DeepReadClaudeProvider

    public init(onProgress: (@Sendable (Int) async -> Void)? = nil) {
        openAI = DeepReadOpenAIProvider(onProgress: onProgress)
        claude = DeepReadClaudeProvider(onProgress: onProgress)
    }

    public func generateText(providerSetting: ProviderSetting, messages: [UIMessage],
                             params: TextGenerationParams) async throws -> MessageChunk {
        if let provider = providerSetting as? ProviderSetting.OpenAI {
            guard provider.authMode == .apiKey else {
                throw AdapterError.unsupportedAuthentication
            }
            return try await openAI.generateText(providerSetting: provider, messages: messages, params: params)
        }
        if let provider = providerSetting as? ProviderSetting.Claude {
            return try await claude.generateText(providerSetting: provider, messages: messages, params: params)
        }
        throw AdapterError.unsupportedProvider
    }

    private enum AdapterError: LocalizedError {
        case unsupportedAuthentication, unsupportedProvider
        var errorDescription: String? {
            switch self {
            case .unsupportedAuthentication: "独立阅读应用目前支持 API Key 认证，请配置 API 服务。"
            case .unsupportedProvider: "当前阅读应用支持 OpenAI 兼容 API 与 Claude API。"
            }
        }
    }
}
