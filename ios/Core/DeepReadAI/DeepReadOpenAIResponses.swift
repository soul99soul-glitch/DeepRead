import Foundation

/// Kotlin `OpenAIKmpProvider` Responses API 路径的纯 Swift 切片。
/// DeepRead 实际形态：无 transcript、无工具声明、无 REASONING 能力标记；
/// 温度恒为 nil（GPT-5 系列等只接受默认温度）。
extension DeepReadOpenAIProvider {
    // MARK: - Responses API

    func responsesGenerateText(
        _ provider: ProviderSetting.OpenAI,
        messages: [UIMessage],
        params: TextGenerationParams
    ) async throws -> MessageChunk {
        let url = provider.baseUrl + "/responses"
        let body = buildResponsesRequestBody(provider, messages: messages, params: params)
        if body["stream"] == .bool(true), let onProgress {
            var stream = DeepReadResponsesStream()
            let jsonBody = try await DeepReadAIHTTP.streamPost(
                url, headers: generationHeaders(provider, messages: messages, params: params),
                body: body.jsonString, failurePrefix: "OpenAI Responses request failed"
            ) { event in
                let count = try stream.consume(event)
                if count > 0 { await onProgress(count) }
            }
            let response: JSONValue
            if let jsonBody {
                guard let json = JSONValue.parse(jsonBody) else {
                    throw DeepReadProviderError("OpenAI Responses request returned invalid JSON.")
                }
                response = json
            } else {
                response = try stream.finalResponse()
            }
            try throwIfResponsesTerminalFailure(response, source: "stream")
            return parseResponseOutput(response)
        }
        let (status, responseBody) = try await DeepReadAIHTTP.post(
            url,
            headers: generationHeaders(provider, messages: messages, params: params),
            body: body.jsonString
        )
        guard DeepReadAIHTTP.isSuccess(status) else {
            throw DeepReadProviderError("OpenAI Responses request failed: \(status) \(responseBody)")
        }
        guard let bodyJson = JSONValue.parse(responseBody) else {
            throw DeepReadProviderError("OpenAI Responses request returned invalid JSON.")
        }
        try throwIfResponsesTerminalFailure(bodyJson, source: "request")
        return parseResponseOutput(bodyJson)
    }

    /// Kotlin `buildResponsesRequestBody` 切片：无 transcript、无工具、
    /// 无 REASONING 能力标记的 DeepRead 形态。
    func buildResponsesRequestBody(
        _ provider: ProviderSetting.OpenAI,
        messages: [UIMessage],
        params: TextGenerationParams
    ) -> JSONValue {
        let isMiMo = isMiMo(provider, host: DeepReadAIHTTP.host(of: provider.baseUrl), modelId: params.model.modelId)

        var body: [String: JSONValue] = [
            "model": .string(params.model.modelId),
            "stream": .bool(onProgress != nil),
        ]
        // MiMo 的兼容层只接受文档内字段，未知键可能被拒（Kotlin 对齐）。
        if !isMiMo { body["store"] = .bool(false) }

        // DeepRead 恒传 temperature/topP = nil（GPT-5 系列等只接受默认温度），
        // Kotlin 的 responsesIsModelAllowTemperature 门控在该前提下不可达。
        if let temperature = params.temperature { body["temperature"] = .number(temperature) }
        if let topP = params.topP { body["top_p"] = .number(topP) }
        if let maxTokens = params.maxTokens {
            body["max_output_tokens"] = .number(Double(maxTokens))
        }

        // 无 transcript 时：全部 system 文本提升到顶层 instructions。
        let systemTexts = messages
            .filter { $0.role == .system }
            .flatMap { message in
                message.parts.compactMap { ($0 as? UIMessagePart.Text)?.text }.filter { !$0.isEmpty }
            }
        if !systemTexts.isEmpty {
            body["instructions"] = .string(systemTexts.joined(separator: "\n\n"))
        }

        // input：system 已提升；user/assistant 文本消息原样入列。
        // assistant 回传与工具项在 DeepRead 单轮管线下不可达，保留 user 形态。
        var inputItems: [JSONValue] = []
        for message in messages where isValidToUpload(message) && message.role != .system {
            let texts = message.parts.compactMap { ($0 as? UIMessagePart.Text)?.text }
            guard !texts.isEmpty else { continue }
            let content: JSONValue = texts.count == 1
                ? .string(texts[0])
                : .array(texts.map { .string($0) })
            inputItems.append(.object([
                "role": .string(message.role.rawValue),
                "content": content,
            ]))
        }
        body["input"] = .array(inputItems)

        // reasoning / tools 块仅在模型声明对应能力时可达；DeepRead 恒无标记。
        return mergeCustomBody(.object(body), params.customBody)
    }

    /// Kotlin `throwIfResponsesTerminalFailure`。`max_output_tokens` 截断是
    /// 协议正常终态，放行给下游的输出上限提示（Kotlin 对齐）。
    func throwIfResponsesTerminalFailure(_ bodyJson: JSONValue, source: String) throws {
        guard case let .object(entries) = bodyJson else { return }
        let status = entries["status"]?.stringValue
        switch status {
        case "incomplete":
            let reason = entries["incomplete_details"]?.objectValue?["reason"]?.stringValue
            if reason == "max_output_tokens" { return }
            throw DeepReadProviderError(
                "OpenAI Responses request incomplete: \(reason ?? entries["status"]?.stringValue ?? "unknown reason")"
            )
        case "failed":
            let detail = entries["error"]?.parseErrorDetail
                ?? entries["status"]?.stringValue
                ?? "unknown error"
            throw DeepReadProviderError("OpenAI Responses request failed: \(detail)")
        default:
            break
        }
    }

    /// Kotlin `parseResponseOutput`。
    func parseResponseOutput(_ bodyJson: JSONValue) -> MessageChunk {
        guard case let .object(entries) = bodyJson else {
            return MessageChunk(id: "", model: "", choices: [
                UIMessageChoice(index: 0, delta: nil, message: UIMessage.assistant(prompt: ""), finishReason: "unknown")
            ])
        }
        var parts: [UIMessagePart] = []
        if case let .array(outputs)? = entries["output"] {
            for output in outputs {
                guard case let .object(item) = output else { continue }
                switch item["type"]?.stringValue {
                case "reasoning":
                    if case let .array(summaries)? = item["summary"] {
                        for summary in summaries {
                            guard case let .object(part) = summary,
                                  part["type"]?.stringValue == "summary_text",
                                  let text = part["text"]?.stringValue else { continue }
                            parts.append(UIMessagePart.Reasoning(reasoning: text))
                        }
                    }
                case "function_call":
                    guard let callId = item["call_id"]?.stringValue,
                          let name = item["name"]?.stringValue else { continue }
                    parts.append(UIMessagePart.Tool(
                        toolCallId: callId,
                        toolName: name,
                        input: item["arguments"]?.stringValue ?? ""
                    ))
                case "message":
                    let text = extractResponsesMessageOutputText(item)
                    if !text.isEmpty {
                        parts.append(UIMessagePart.Text(text: text))
                    }
                    if case let .array(content)? = item["content"] {
                        for block in content {
                            if let refusal = block.objectValue?["refusal"]?.stringValue, !refusal.isBlank {
                                parts.append(UIMessagePart.Text(text: refusal))
                            }
                        }
                    }
                case "output_text":
                    if let text = item["text"]?.stringValue, !text.isEmpty {
                        parts.append(UIMessagePart.Text(text: text))
                    }
                case "refusal":
                    if let refusal = item["refusal"]?.stringValue, !refusal.isBlank {
                        parts.append(UIMessagePart.Text(text: refusal))
                    }
                default:
                    break
                }
            }
        }
        let finishReason = entries["incomplete_details"]?.objectValue?["reason"]?.stringValue
            ?? entries["status"]?.stringValue.flatMap { $0 == "completed" ? nil : $0 }
        let usage = entries["usage"]?.objectValue.map { usage in
            TokenUsage(
                promptTokens: usage["input_tokens"]?.intValue ?? 0,
                completionTokens: usage["output_tokens"]?.intValue ?? 0,
                cachedTokens: usage["input_tokens_details"]?.objectValue?["cached_tokens"]?.intValue ?? 0,
                totalTokens: usage["total_tokens"]?.intValue ?? 0
            )
        }
        return MessageChunk(
            id: entries["id"]?.stringValue ?? "",
            model: entries["model"]?.stringValue ?? "",
            choices: [UIMessageChoice(index: 0, delta: nil, message: UIMessage(role: .assistant, parts: parts), finishReason: finishReason)],
            usage: usage
        )
    }

    /// Kotlin `extractResponsesMessageOutputText`。
    func extractResponsesMessageOutputText(_ item: [String: JSONValue]) -> String {
        guard case let .array(content)? = item["content"] else { return "" }
        return content.compactMap { block -> String? in
            guard case let .object(part) = block else { return nil }
            switch part["type"]?.stringValue {
            case "output_text", "text":
                return part["text"]?.stringValue
            default:
                return nil
            }
        }
        .joined(separator: "")
    }
}
