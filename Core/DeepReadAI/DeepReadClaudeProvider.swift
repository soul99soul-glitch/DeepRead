import Foundation

/// Kotlin `ClaudeKmpProvider` 的纯 Swift 切片。
///
/// 忠实移植 DeepRead 管线实际走到的路径：
/// - `/messages` 按需流式接收，`x-api-key` + `anthropic-version: 2023-06-01`；
/// - system 消息扁平化到顶层 `system` 数组（无工具 transcript 形态）；
/// - `promptCaching` 的 ephemeral 标记（system 末位 + 倒数第二条 user 消息）；
/// - aihubmix / openrouter 的 referer 头；OpenCode 端点头；
/// - 响应解析含 thinking / tool_use（后者用于诚实失败判定）。
///
/// 有意省略（DeepRead 恒不触发）：
/// - 图片内容块、多 key 轮换；
/// - 工具声明与 `anthropic-beta: mid-conversation-tool-changes`（需 TOOL 能力）；
/// - thinking 请求块（需 REASONING 能力标记，DeepRead 构造的模型恒无）；
/// - `listModels`（DeepRead 不做连接探测）。
public struct DeepReadClaudeProvider: IOSAgentTextProvider {
    /// Kotlin `ANTHROPIC_VERSION`。
    static let anthropicVersion = "2023-06-01"

    let onProgress: (@Sendable (Int) async -> Void)?

    public init(onProgress: (@Sendable (Int) async -> Void)? = nil) {
        self.onProgress = onProgress
    }

    public func generateText(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams
    ) async throws -> MessageChunk {
        guard let claude = providerSetting as? ProviderSetting.Claude else {
            throw DeepReadProviderError("Claude provider requires a Claude provider setting.")
        }
        let body = buildMessageRequest(claude, messages: messages, params: params)
        var headers = generationHeaders(claude, messages: messages, params: params)
        headers.append(CustomHeader(name: "x-api-key", value: claude.apiKey))
        headers.append(CustomHeader(name: "anthropic-version", value: Self.anthropicVersion))
        headers.append(contentsOf: refererHeaders(for: claude.baseUrl))

        if body["stream"] == .bool(true), let onProgress {
            var stream = DeepReadClaudeStream()
            let jsonBody = try await DeepReadAIHTTP.streamPost(
                claude.baseUrl + "/messages", headers: headers,
                body: body.jsonString, failurePrefix: "Claude request failed"
            ) { event in
                let count = try stream.consume(event)
                if count > 0 { await onProgress(count) }
            }
            if let jsonBody {
                guard let json = JSONValue.parse(jsonBody), json.objectValue != nil else {
                    throw DeepReadProviderError("Claude request returned invalid JSON.")
                }
                return parseMessageChunk(json)
            }
            return parseMessageChunk(try stream.finalResponse())
        }

        let (status, responseBody) = try await DeepReadAIHTTP.post(
            claude.baseUrl + "/messages",
            headers: headers,
            body: body.jsonString
        )
        guard DeepReadAIHTTP.isSuccess(status) else {
            throw DeepReadProviderError("Claude request failed: \(status) \(responseBody)")
        }
        guard let bodyJson = JSONValue.parse(responseBody), case let .object(entries) = bodyJson else {
            throw DeepReadProviderError("Claude request returned invalid JSON.")
        }
        return parseMessageChunk(.object(entries))
    }

    private func parseMessageChunk(_ body: JSONValue) -> MessageChunk {
        let entries = body.objectValue ?? [:]
        let content = entries["content"]?.arrayValue ?? []
        return MessageChunk(
            id: entries["id"]?.stringValue ?? "",
            model: entries["model"]?.stringValue ?? "",
            choices: [UIMessageChoice(
                index: 0,
                delta: nil,
                message: parseMessage(content),
                finishReason: entries["stop_reason"]?.stringValue ?? "unknown"
            )],
            usage: parseTokenUsage(entries)
        )
    }

    // MARK: - 请求构建

    /// Kotlin `buildMessageRequest` 切片（无 transcript / 无工具形态）：
    /// system 扁平化到顶层 `system`，其余消息进入 `messages`。
    func buildMessageRequest(
        _ provider: ProviderSetting.Claude,
        messages: [UIMessage],
        params: TextGenerationParams
    ) -> JSONValue {
        var messageItems: [JSONValue] = []
        var systemBlocks: [JSONValue] = []
        for message in messages where message.role != .system {
            guard isValidToUpload(message) else { continue }
            let blocks = contentBlocks(of: message)
            guard !blocks.isEmpty else { continue }
            messageItems.append(.object([
                "role": .string(message.role.rawValue),
                "content": .array(blocks),
            ]))
        }
        // Kotlin：promptCaching 开启时对 messages 数组做缓存标记后处理。
        if provider.promptCaching {
            messageItems = withMessagesCacheControl(messageItems)
        }
        for message in messages where message.role == .system {
            for part in message.parts {
                guard let text = (part as? UIMessagePart.Text)?.text, !text.isEmpty else { continue }
                systemBlocks.append(.object(["type": .string("text"), "text": .string(text)]))
            }
        }

        var body: [String: JSONValue] = [
            "model": .string(params.model.modelId),
            "messages": .array(messageItems),
            // Kotlin：max_tokens 缺省 64_000。
            "max_tokens": .number(Double(params.maxTokens ?? 64_000)),
            "stream": .bool(onProgress != nil),
        ]
        // temperature 仅在非思考模式下下发（Kotlin 对齐；DeepRead 恒 OFF）。
        if let temperature = params.temperature, !params.reasoningLevel.isEnabled {
            body["temperature"] = .number(temperature)
        }
        if let topP = params.topP {
            body["top_p"] = .number(topP)
        }
        if !systemBlocks.isEmpty {
            if provider.promptCaching, let lastIndex = systemBlocks.indices.last {
                systemBlocks[lastIndex] = addingEphemeralCacheControl(to: systemBlocks[lastIndex])
            }
            body["system"] = .array(systemBlocks)
        }
        return mergeCustomBody(.object(body), params.customBody)
    }

    /// Kotlin `buildMessages` 切片：assistant/user 消息的内容块。
    /// Reasoning 无 signature 元数据时 Kotlin `toContentBlock` 返回 null 跳过，
    /// 此处对齐（DeepRead 不回传思考块）。
    func contentBlocks(of message: UIMessage) -> [JSONValue] {
        message.parts.compactMap { part -> JSONValue? in
            guard let text = (part as? UIMessagePart.Text)?.text else { return nil }
            return .object(["type": .string("text"), "text": .string(text)])
        }
    }

    /// Kotlin `insertMessagesCacheControl`：在倒数第二个非 tool_result 的
    /// user 消息的最后一个内容块上加 ephemeral 缓存标记（DeepRead 单轮
    /// user 消息通常只有一条，此时不插入——与 Kotlin 行为一致）。
    func withMessagesCacheControl(_ messages: [JSONValue]) -> [JSONValue] {
        let realUserIndices = messages.enumerated().compactMap { index, message -> Int? in
            guard case let .object(entries) = message,
                  entries["role"]?.stringValue == "user" else { return nil }
            let blocks = entries["content"]?.arrayValue ?? []
            let isToolResult = blocks.contains { $0.objectValue?["type"]?.stringValue == "tool_result" }
            return isToolResult ? nil : index
        }
        guard realUserIndices.count >= 2 else { return messages }
        let targetIndex = realUserIndices[realUserIndices.count - 2]
        return messages.enumerated().map { index, message -> JSONValue in
            guard index == targetIndex, case var .object(entries) = message else { return message }
            var content = entries["content"]?.arrayValue ?? []
            if let last = content.indices.last {
                content[last] = addingEphemeralCacheControl(to: content[last])
                entries["content"] = .array(content)
            }
            return .object(entries)
        }
    }

    func addingEphemeralCacheControl(to block: JSONValue) -> JSONValue {
        guard case var .object(entries) = block else { return block }
        entries["cache_control"] = .object(["type": .string("ephemeral")])
        return .object(entries)
    }

    /// Kotlin `UIMessage.isValidToUpload` 切片。
    func isValidToUpload(_ message: UIMessage) -> Bool {
        message.parts.contains { part in
            if let text = part as? UIMessagePart.Text { return !text.text.isBlank }
            return true
        }
    }

    // MARK: - 请求头

    /// OpenCode 端点头（会话 + UA），与 Kotlin `generationHeaders` 一致；
    /// anthropic-beta 工具变更头需 TOOL 能力，DeepRead 不可达，省略。
    func generationHeaders(
        _ provider: ProviderSetting.Claude,
        messages: [UIMessage],
        params: TextGenerationParams
    ) -> [CustomHeader] {
        OpenCodeRequestHeaders.forGeneration(
            baseURL: provider.baseUrl,
            messages: messages,
            customHeaders: params.customHeaders
        )
    }

    /// Kotlin `configureReferHeaders`：聚合网关的署名头。
    func refererHeaders(for baseURL: String) -> [CustomHeader] {
        switch DeepReadAIHTTP.host(of: baseURL) {
        case "aihubmix.com":
            return [CustomHeader(name: "APP-Code", value: "DKHA9468")]
        case "openrouter.ai":
            return [
                CustomHeader(name: "X-Title", value: "AmberAgent"),
                CustomHeader(name: "HTTP-Referer", value: "https://github.com"),
            ]
        default:
            return []
        }
    }

    // MARK: - 响应解析

    /// Kotlin `parseMessage`：text/thinking/tool_use 内容块 → 消息部件。
    /// thinking 的签名/块序号元数据 DeepRead 不消费，省略。
    func parseMessage(_ content: [JSONValue]) -> UIMessage {
        var parts: [UIMessagePart] = []
        for block in content {
            guard case let .object(entries) = block else { continue }
            switch entries["type"]?.stringValue {
            case "text", "text_delta":
                let text = entries["text"]?.stringValue ?? ""
                if !text.isEmpty {
                    parts.append(UIMessagePart.Text(text: text))
                }
            case "thinking", "thinking_delta", "signature_delta":
                let thinking = entries["thinking"]?.stringValue ?? ""
                let signature = entries["signature"]?.stringValue
                if !thinking.isEmpty || signature != nil {
                    parts.append(UIMessagePart.Reasoning(reasoning: thinking))
                }
            case "redacted_thinking":
                parts.append(UIMessagePart.Reasoning(reasoning: ""))
            case "tool_use":
                let inputJson = entries["input"]?.objectValue ?? [:]
                parts.append(UIMessagePart.Tool(
                    toolCallId: entries["id"]?.stringValue ?? "",
                    toolName: entries["name"]?.stringValue ?? "",
                    input: inputJson.isEmpty ? "" : JSONValue.object(inputJson).jsonString
                ))
            case "input_json_delta":
                parts.append(UIMessagePart.Tool(
                    toolCallId: "",
                    toolName: "",
                    input: entries["partial_json"]?.stringValue ?? ""
                ))
            default:
                break
            }
        }
        return UIMessage(role: .assistant, parts: parts)
    }

    /// Kotlin `parseTokenUsage`：input + 缓存读取/创建 tokens 合成 prompt 用量。
    func parseTokenUsage(_ bodyJson: [String: JSONValue]) -> TokenUsage? {
        let usage = bodyJson["usage"]?.objectValue
            ?? bodyJson["message"]?.objectValue?["usage"]?.objectValue
        guard let usage else { return nil }
        let inputTokens = usage["input_tokens"]?.intValue ?? 0
        let cachedInputTokens = usage["cache_read_input_tokens"]?.intValue ?? 0
        let cachedCreationTokens = usage["cache_creation_input_tokens"]?.intValue ?? 0
        let completionTokens = usage["output_tokens"]?.intValue ?? 0
        let promptTokens = inputTokens + cachedInputTokens + cachedCreationTokens
        return TokenUsage(
            promptTokens: promptTokens,
            completionTokens: completionTokens,
            cachedTokens: cachedInputTokens,
            totalTokens: promptTokens + completionTokens
        )
    }

    // MARK: - 公共

    /// Kotlin `JsonObject.mergeCustomBody`：自定义 body 顶层键覆写。
    func mergeCustomBody(_ body: JSONValue, _ customBody: [CustomBody]) -> JSONValue {
        guard !customBody.isEmpty, case var .object(entries) = body else { return body }
        for entry in customBody {
            entries[entry.key] = entry.value
        }
        return .object(entries)
    }
}
