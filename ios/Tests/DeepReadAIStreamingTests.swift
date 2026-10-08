import XCTest
@testable import AmberDeepRead

final class DeepReadAIStreamingTests: XCTestCase {
    func testSSEFramingHandlesCommentsCRLFAndMultipleDataLines() throws {
        var decoder = DeepReadSSEDecoder()
        XCTAssertNil(decoder.consume(": keepalive\r"))
        XCTAssertNil(decoder.consume("event: response.output_text.delta\r"))
        XCTAssertNil(decoder.consume("data: {\"type\":\"response.output_text.delta\",\r"))
        XCTAssertNil(decoder.consume("data: \"delta\":\"中文😀\"}\r"))
        let event = try XCTUnwrap(decoder.consume("\r"))
        XCTAssertEqual(event.name, "response.output_text.delta")
        XCTAssertEqual(JSONValue.parse(event.data)?["delta"]?.stringValue, "中文😀")
        XCTAssertNil(decoder.consume(""))
    }

    func testByteLineDecodingPreservesEmptyBoundariesAndFragmentedUnicode() {
        var lines = DeepReadSSELines()
        let fixture = "data: 中文😀\r\n\r\n: ping\n\ndata: next\r\rdata: final"
        var decoded = fixture.utf8.compactMap { lines.consume($0) }
        if let last = lines.flush() { decoded.append(last) }
        XCTAssertEqual(decoded, ["data: 中文😀", "", ": ping", "", "data: next", "", "data: final"])
    }

    func testChatAggregatesReasoningTextToolArgumentsAndLengthUsage() throws {
        var stream = DeepReadChatStream()
        XCTAssertEqual(try stream.consume(event(#"{"id":"c","model":"m","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"思考"}}]}"#)), 2)
        XCTAssertEqual(try stream.consume(event(#"{"choices":[{"index":1,"delta":{"content":"ignored"}},{"index":0,"delta":{"content":"中文😀","tool_calls":[{"index":0,"id":"t","function":{"name":"search","arguments":"{\"q\":"}}]}}]}"#)), 8)
        XCTAssertEqual(try stream.consume(event(#"{"choices":[{"index":0,"delta":{"content":"","tool_calls":[{"index":0,"function":{"arguments":"\"x\"}"}}]},"finish_reason":"length"}],"usage":{"prompt_tokens":3,"completion_tokens":7,"total_tokens":10}}"#)), 4)
        XCTAssertEqual(try stream.consume(event("[DONE]")), 0)
        let chunk = try DeepReadOpenAIProvider().parseChatCompletionChunk(stream.finalResponse())
        XCTAssertEqual(chunk.choices.first?.message?.toText(), "中文😀")
        XCTAssertEqual(chunk.choices.first?.finishReason, "length")
        XCTAssertEqual(chunk.usage?.completionTokens, 7)
        let tool = chunk.choices.first?.message?.parts.compactMap { $0 as? UIMessagePart.Tool }.first
        XCTAssertEqual(tool?.input, #"{"q":"x"}"#)
        XCTAssertEqual(tool?.toolName, "search")
    }

    func testChatRejectsPrematureEOFAndStreamError() throws {
        var stream = DeepReadChatStream()
        _ = try stream.consume(event(#"{"choices":[{"delta":{"content":"partial"}}]}"#))
        XCTAssertThrowsError(try stream.finalResponse())
        XCTAssertThrowsError(try stream.consume(event(#"{"error":{"message":"quota exceeded"}}"#))) { error in
            XCTAssertTrue(error.localizedDescription.contains("quota exceeded"))
        }
    }

    func testResponsesOnlyCountDeltasAndUseTerminalResponseForToolAndLimit() throws {
        var stream = DeepReadResponsesStream()
        XCTAssertEqual(try stream.consume(event(#"{"type":"response.created","response":{"status":"in_progress"}}"#)), 0)
        XCTAssertEqual(try stream.consume(event(#"{"type":"response.output_text.delta","delta":"正文"}"#)), 2)
        XCTAssertEqual(try stream.consume(event(#"{"type":"response.reasoning_summary_text.delta","delta":"原因"}"#)), 2)
        XCTAssertEqual(try stream.consume(event(#"{"type":"response.function_call_arguments.delta","delta":"{}"}"#)), 2)
        XCTAssertEqual(try stream.consume(event(#"{"type":"response.output_text.done","text":"正文"}"#)), 0)
        XCTAssertThrowsError(try stream.finalResponse())
        _ = try stream.consume(event(#"{"type":"response.incomplete","response":{"id":"r","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[{"type":"message","content":[{"type":"output_text","text":"正文"}]},{"type":"function_call","call_id":"t","name":"tool","arguments":"{}"}],"usage":{"input_tokens":4,"output_tokens":2,"total_tokens":6}}}"#))
        let response = try stream.finalResponse()
        let provider = DeepReadOpenAIProvider()
        try provider.throwIfResponsesTerminalFailure(response, source: "stream")
        let chunk = provider.parseResponseOutput(response)
        XCTAssertEqual(chunk.choices.first?.finishReason, "max_output_tokens")
        XCTAssertEqual(chunk.usage?.totalTokens, 6)
        XCTAssertEqual(chunk.choices.first?.message?.parts.compactMap { $0 as? UIMessagePart.Tool }.count, 1)
        var failed = DeepReadResponsesStream()
        _ = try failed.consume(event(#"{"type":"response.failed","response":{"status":"failed","error":{"message":"failed generation"}}}"#))
        XCTAssertThrowsError(try provider.throwIfResponsesTerminalFailure(failed.finalResponse(), source: "stream"))
    }

    func testClaudeAggregatesBlocksAndOverwritesUsageWithoutCountingPingOrSignature() throws {
        var stream = DeepReadClaudeStream()
        XCTAssertEqual(try stream.consume(event(#"{"type":"ping"}"#)), 0)
        _ = try stream.consume(event(#"{"type":"message_start","message":{"id":"a","model":"c","content":[],"usage":{"input_tokens":5,"cache_read_input_tokens":3,"output_tokens":1}}}"#))
        _ = try stream.consume(event(#"{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}"#))
        XCTAssertEqual(try stream.consume(event(#"{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"思考"}}"#)), 2)
        XCTAssertEqual(try stream.consume(event(#"{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"signed"}}"#)), 0)
        _ = try stream.consume(event(#"{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}"#))
        XCTAssertEqual(try stream.consume(event(#"{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"正文😀"}}"#)), 3)
        _ = try stream.consume(event(#"{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"t","name":"search","input":{}}}"#))
        XCTAssertEqual(try stream.consume(event(#"{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"q\":\"x\"}"}}"#)), 9)
        _ = try stream.consume(event(#"{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":9}}"#))
        XCTAssertThrowsError(try stream.finalResponse())
        _ = try stream.consume(event(#"{"type":"message_stop"}"#))
        let response = try XCTUnwrap(try stream.finalResponse().objectValue)
        let provider = DeepReadClaudeProvider()
        XCTAssertEqual(provider.parseMessage(response["content"]?.arrayValue ?? []).toText(), "正文😀")
        XCTAssertEqual(provider.parseTokenUsage(response)?.promptTokens, 8)
        XCTAssertEqual(provider.parseTokenUsage(response)?.completionTokens, 9)
        XCTAssertEqual(response["stop_reason"]?.stringValue, "tool_use")
        XCTAssertEqual(response["content"]?.arrayValue?.first?["signature"]?.stringValue, "signed")
        XCTAssertEqual(provider.parseMessage(response["content"]?.arrayValue ?? []).parts.compactMap { $0 as? UIMessagePart.Tool }.first?.input, #"{"q":"x"}"#)
    }

    func testStreamingIsOptInAndExplicitCustomFalseWinsForAllProtocols() {
        let params = TextGenerationParams(model: Model(modelId: "m"))
        let override = TextGenerationParams(model: Model(modelId: "m"), customBody: [CustomBody(key: "stream", value: .bool(false))])
        let openAI = ProviderSetting.OpenAI()
        let claude = ProviderSetting.Claude()
        XCTAssertEqual(DeepReadOpenAIProvider().buildChatCompletionRequest(openAI, messages: [], params: params)["stream"], .bool(false))
        let streamingOpenAI = DeepReadOpenAIProvider(onProgress: { _ in })
        XCTAssertEqual(streamingOpenAI.buildChatCompletionRequest(openAI, messages: [], params: params)["stream"], .bool(true))
        XCTAssertEqual(streamingOpenAI.buildChatCompletionRequest(openAI, messages: [], params: override)["stream"], .bool(false))
        XCTAssertEqual(streamingOpenAI.buildResponsesRequestBody(openAI, messages: [], params: params)["stream"], .bool(true))
        XCTAssertEqual(streamingOpenAI.buildResponsesRequestBody(openAI, messages: [], params: override)["stream"], .bool(false))
        let streamingClaude = DeepReadClaudeProvider(onProgress: { _ in })
        XCTAssertEqual(DeepReadClaudeProvider().buildMessageRequest(claude, messages: [], params: params)["stream"], .bool(false))
        XCTAssertEqual(streamingClaude.buildMessageRequest(claude, messages: [], params: params)["stream"], .bool(true))
        XCTAssertEqual(streamingClaude.buildMessageRequest(claude, messages: [], params: override)["stream"], .bool(false))
    }

    func testResponsesAndClaudeRejectPrematureEOFAndProtocolErrors() throws {
        var responses = DeepReadResponsesStream()
        _ = try responses.consume(event(#"{"type":"response.output_text.delta","delta":"partial"}"#))
        XCTAssertThrowsError(try responses.finalResponse())
        XCTAssertThrowsError(try responses.consume(event(#"{"type":"error","message":"response failed"}"#)))
        var claude = DeepReadClaudeStream()
        _ = try claude.consume(event(#"{"type":"message_start","message":{"id":"c","content":[]}}"#))
        XCTAssertThrowsError(try claude.finalResponse())
        XCTAssertThrowsError(try claude.consume(event(#"{"type":"error","error":{"type":"overloaded_error","message":"overloaded"}}"#))) { error in
            XCTAssertTrue(error.localizedDescription.contains("overloaded"))
        }
    }

    private func event(_ data: String) -> DeepReadSSEEvent {
        DeepReadSSEEvent(name: nil, data: data)
    }
}
