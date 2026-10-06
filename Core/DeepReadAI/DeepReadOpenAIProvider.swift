import Foundation

/// Kotlin `OpenAIKmpProvider` 的纯 Swift 切片。
///
/// 忠实移植 DeepRead 管线实际走到的路径：
/// - Chat Completions 与 Responses API，按需流式接收并返回完整响应；
/// - Bearer / MiMo `api-key` 认证、OpenCode 端点头、自定义头合并；
/// - 响应解析含 reasoning_content 与 tool_calls（后者用于诚实失败判定）；
/// - 自定义 body 顶层键覆写。
///
/// 有意省略（DeepRead 永不触发，见类注释）：
/// - 图片输入/输出、工具声明与 MiMo 正文工具调用兜底；
/// - OAuth / Coding Plan 端点流式适配（DeepRead 只产生 `api_key` + `generic`）；
/// - `listModels`（DeepRead 设置界面不做连接探测）。
public struct DeepReadOpenAIProvider: IOSAgentTextProvider {
    let onProgress: (@Sendable (Int) async -> Void)?

    public init(onProgress: (@Sendable (Int) async -> Void)? = nil) {
        self.onProgress = onProgress
    }

    /// SiliconFlow 混合思考模型白名单：reasoning=OFF 时必须显式关闭思考，
    /// 否则结构化生成会被服务端默认开启的思考拖慢数倍。
    /// 直译自 Kotlin `siliconFlowThinkingModels`。
    static let siliconFlowThinkingModels: Set<String> = [
        "Pro/moonshotai/Kimi-K2.5",
        "Pro/zai-org/GLM-5",
        "Pro/zai-org/GLM-5.1",
        "Pro/zai-org/GLM-4.7",
        "deepseek-ai/DeepSeek-V3.2",
        "Pro/deepseek-ai/DeepSeek-V3.2",
        "Qwen/Qwen3.5-397B-A17B",
        "Qwen/Qwen3.5-122B-A10B",
        "Qwen/Qwen3.5-35B-A3B",
        "Qwen/Qwen3.5-27B",
        "Qwen/Qwen3.5-9B",
        "Qwen/Qwen3.5-4B",
        "zai-org/GLM-4.6",
        "Qwen/Qwen3-8B",
        "Qwen/Qwen3-14B",
        "Qwen/Qwen3-32B",
        "Qwen/Qwen3-30B-A3B",
        "tencent/Hunyuan-A13B-Instruct",
        "zai-org/GLM-4.5V",
        "deepseek-ai/DeepSeek-V3.1-Terminus",
        "Pro/deepseek-ai/DeepSeek-V3.1-Terminus",
        "deepseek-ai/DeepSeek-V4-Flash",
        "Pro/deepseek-ai/DeepSeek-V4-Flash",
        "deepseek-ai/DeepSeek-V4-Pro",
        "Pro/deepseek-ai/DeepSeek-V4-Pro",
    ]

    // MARK: - 入口

    public func generateText(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams
    ) async throws -> MessageChunk {
        guard let openAI = providerSetting as? ProviderSetting.OpenAI else {
            throw DeepReadProviderError("OpenAI provider requires an OpenAI-compatible provider setting.")
        }
        if usesResponsesAPI(openAI, modelId: params.model.modelId) {
            return try await responsesGenerateText(openAI, messages: messages, params: params)
        }
        return try await chatCompletionsGenerateText(openAI, messages: messages, params: params)
    }

    /// Kotlin `usesOpenAIResponsesApi`。Codex OAuth 分支保留判断但 DeepRead
    /// 不会产生该认证模式。
    func usesResponsesAPI(_ provider: ProviderSetting.OpenAI, modelId: String) -> Bool {
        if provider.useResponseApi || provider.authMode == .codexOAuth { return true }
        let wireId = modelId.split(separator: "/").last.map(String.init)?.lowercased() ?? ""
        return wireId.hasPrefix("muse-spark")
    }

    /// Kotlin `isMiMoProvider`。
    func isMiMo(_ provider: ProviderSetting.OpenAI, host: String, modelId: String) -> Bool {
        provider.brand == .mimo ||
            provider.authMode == .mimoCodingPlan ||
            host.hasSuffix("xiaomimimo.com") ||
            modelId.lowercased().contains("mimo")
    }

    // MARK: - Chat Completions

    func chatCompletionsGenerateText(
        _ provider: ProviderSetting.OpenAI,
        messages: [UIMessage],
        params: TextGenerationParams
    ) async throws -> MessageChunk {
        let url = provider.baseUrl + provider.chatCompletionsPath
        let body = buildChatCompletionRequest(provider, messages: messages, params: params)
        if body["stream"] == .bool(true), let onProgress {
            var stream = DeepReadChatStream()
            let jsonBody = try await DeepReadAIHTTP.streamPost(
                url, headers: generationHeaders(provider, messages: messages, params: params),
                body: body.jsonString, failurePrefix: "OpenAI request failed"
            ) { event in
                let count = try stream.consume(event)
                if count > 0 { await onProgress(count) }
            }
            if let jsonBody {
                guard let json = JSONValue.parse(jsonBody) else {
                    throw DeepReadProviderError("OpenAI request returned invalid JSON.")
                }
                return try parseChatCompletionChunk(json)
            }
            return try parseChatCompletionChunk(stream.finalResponse())
        }
        let (status, responseBody) = try await DeepReadAIHTTP.post(
            url,
            headers: generationHeaders(provider, messages: messages, params: params),
            body: body.jsonString
        )
        guard DeepReadAIHTTP.isSuccess(status) else {
            throw DeepReadProviderError("OpenAI request failed: \(status) \(responseBody)")
        }
        guard let bodyJson = JSONValue.parse(responseBody) else {
            throw DeepReadProviderError("OpenAI request returned invalid JSON.")
        }
        return try parseChatCompletionChunk(bodyJson)
    }

    /// Kotlin `buildChatCompletionRequest` 切片。
    func buildChatCompletionRequest(
        _ provider: ProviderSetting.OpenAI,
        messages: [UIMessage],
        params: TextGenerationParams
    ) -> JSONValue {
        let host = DeepReadAIHTTP.host(of: provider.baseUrl)
        let isMiMo = isMiMo(provider, host: host, modelId: params.model.modelId)

        var body: [String: JSONValue] = [
            "model": .string(params.model.modelId),
            "messages": .array(buildChatCompletionMessages(messages)),
            "stream": .bool(onProgress != nil),
        ]
        if let temperature = params.temperature {
            body["temperature"] = .number(temperature)
        }
        if let topP = params.topP {
            body["top_p"] = .number(topP)
        }
        if let maxTokens = params.maxTokens {
            body[isMiMo ? "max_completion_tokens" : "max_tokens"] = .number(Double(maxTokens))
        }

        // SiliconFlow 思考模型：reasoning=OFF 时显式下发关闭字段（Kotlin 对齐）。
        let forceDisableThinking = params.reasoningLevel == .off &&
            host == "api.siliconflow.cn" &&
            Self.siliconFlowThinkingModels.contains(params.model.modelId)
        if params.model.abilities.contains(.reasoning) || forceDisableThinking {
            if host == "api.siliconflow.cn" {
                body["enable_thinking"] = .bool(params.reasoningLevel.isEnabled)
            }
            // 非 SiliconFlow 的 thinking 字段规划（planOpenAICompatibleThinking）
            // 仅在模型声明 REASONING 能力时可达；DeepRead 构造的模型恒无能力
            // 标记，整块省略。
        }

        // MiniMax 默认把思考写进正文 <think> 标签；reasoning_split 让它走
        // reasoning_content（Kotlin 对齐，不看能力标记）。
        if provider.brand == .minimax ||
            host.hasSuffix("minimaxi.com") ||
            host.hasSuffix("minimax.io") {
            body["reasoning_split"] = .bool(true)
        }

        // DeepRead 无工具：toolPlan/parallel_tool_calls 分支恒不可达，省略。
        return mergeCustomBody(.object(body), params.customBody)
    }

    /// Kotlin `buildMessages` + `addNonAssistantMessage` 切片。DeepRead 请求
    /// 只含 system/user 文本；assistant 回传保留简化形态（纯 Text 合并），
    /// tool 边界分组在无工具管线中不可达。
    func buildChatCompletionMessages(_ messages: [UIMessage]) -> [JSONValue] {
        messages.filter { isValidToUpload($0) }.map { message -> JSONValue in
            let texts = message.parts.compactMap { ($0 as? UIMessagePart.Text)?.text }
            var entry: [String: JSONValue] = ["role": .string(message.role.rawValue)]
            switch (message.role, texts.count) {
            case (.system, 0):
                entry["content"] = .string("")
            case (.system, _):
                entry["content"] = .string(texts.joined(separator: "\n\n"))
            case (_, 0):
                entry["content"] = .string("")
            case (_, 1):
                entry["content"] = .string(texts[0])
            default:
                entry["content"] = .array(texts.map { .string($0) })
            }
            return .object(entry)
        }
    }

    /// Kotlin `UIMessage.isValidToUpload` 切片（DeepRead 无图片/文档部件）。
    func isValidToUpload(_ message: UIMessage) -> Bool {
        message.parts.contains { part in
            if let text = part as? UIMessagePart.Text { return !text.text.isBlank }
            return true
        }
    }

    // MARK: - Chat Completions 响应解析

    func parseChatCompletionChunk(_ bodyJson: JSONValue) throws -> MessageChunk {
        guard case let .object(entries) = bodyJson else {
            throw DeepReadProviderError("OpenAI response is not a JSON object.")
        }
        let id = entries["id"]?.stringValue ?? ""
        let model = entries["model"]?.stringValue ?? ""
        guard case let .array(choices)? = entries["choices"], let first = choices.first,
              case let .object(choice) = first else {
            throw DeepReadProviderError("OpenAI request failed: choices is null")
        }
        guard case let .object(messageJson)? = choice["message"] else {
            throw DeepReadProviderError("OpenAI request failed: message is null")
        }
        let message = parseChatCompletionMessage(.object(messageJson))
        let finishReason = choice["finish_reason"]?.stringValue ?? "unknown"
        let usage = entries["usage"]?.objectValue.flatMap(parseTokenUsage)
        return MessageChunk(
            id: id,
            model: model,
            choices: [UIMessageChoice(index: 0, delta: nil, message: message, finishReason: finishReason)],
            usage: usage
        )
    }

    /// Kotlin `parseMessage`：reasoning → Reasoning，tool_calls → Tool，
    /// 正文 → Text。annotations 解析省略（DeepRead 不读引用列表）。
    func parseChatCompletionMessage(_ json: JSONValue) -> UIMessage {
        var parts: [UIMessagePart] = []
        let reasoning = json["reasoning_content"]?.stringValue ?? json["reasoning"]?.stringValue
        if json["reasoning_content"] != nil || !(reasoning ?? "").isEmpty {
            parts.append(UIMessagePart.Reasoning(reasoning: reasoning ?? ""))
        }
        if case let .array(toolCalls)? = json["tool_calls"] {
            for call in toolCalls {
                guard case let .object(callObj) = call else { continue }
                let function = callObj["function"]?.objectValue
                parts.append(
                    UIMessagePart.Tool(
                        toolCallId: callObj["id"]?.stringValue ?? "",
                        toolName: function?["name"]?.stringValue ?? "",
                        input: function?["arguments"]?.stringValue ?? "",
                        streamIndex: callObj["index"]?.intValue
                    )
                )
            }
        }
        let content = json["content"]?.stringValue ?? ""
        if !content.isEmpty {
            parts.append(UIMessagePart.Text(text: content))
        }
        return UIMessage(role: .init(parsing: json["role"]?.stringValue), parts: parts)
    }

    /// Kotlin `parseTokenUsage`（Chat Completions 形态）。
    func parseTokenUsage(_ usage: [String: JSONValue]) -> TokenUsage {
        let cacheHit = usage["prompt_cache_hit_tokens"]?.intValue
        let cacheMiss = usage["prompt_cache_miss_tokens"]?.intValue
        let promptTokens = usage["prompt_tokens"]?.intValue
            ?? {
                let parts = [cacheHit, cacheMiss].compactMap { $0 }
                return parts.isEmpty ? 0 : parts.reduce(0, +)
            }()
        let cachedTokens = usage["prompt_tokens_details"]?.objectValue?["cached_tokens"]?.intValue
            ?? cacheHit ?? 0
        return TokenUsage(
            promptTokens: promptTokens,
            completionTokens: usage["completion_tokens"]?.intValue ?? 0,
            cachedTokens: cachedTokens,
            totalTokens: usage["total_tokens"]?.intValue ?? 0
        )
    }

    // MARK: - 公共

    /// Kotlin `configureGenerationAuth`：OpenCode 端点头作为 extraHeaders
    /// 流入认证头合并（大小写不敏感、后写优先），与 Kotlin 链路一致。
    func generationHeaders(
        _ provider: ProviderSetting.OpenAI,
        messages: [UIMessage],
        params: TextGenerationParams
    ) -> [CustomHeader] {
        let extra = OpenCodeRequestHeaders.forGeneration(
            baseURL: provider.baseUrl,
            messages: messages,
            customHeaders: params.customHeaders
        )
        return DeepReadOpenAIHeaders.authenticationHeaders(for: provider, extraHeaders: extra)
    }

    /// Kotlin `JsonObject.mergeCustomBody`：自定义 body 顶层键覆写。
    func mergeCustomBody(_ body: JSONValue, _ customBody: [CustomBody]) -> JSONValue {
        guard !customBody.isEmpty, case var .object(entries) = body else { return body }
        for entry in customBody {
            entries[entry.key] = entry.value
        }
        return .object(entries)
    }
}
