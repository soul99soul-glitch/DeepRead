import Foundation

struct DeepReadSSEEvent {
    let name: String?
    let data: String

    func jsonObject() throws -> [String: JSONValue] {
        guard let object = JSONValue.parse(data)?.objectValue else {
            throw DeepReadProviderError("Provider stream returned invalid JSON.")
        }
        if let error = object["error"], error != .null {
            throw DeepReadProviderError("Provider stream failed: \(error.parseErrorDetail)")
        }
        if object["type"]?.stringValue == "error" || name == "error" {
            throw DeepReadProviderError("Provider stream failed: \(JSONValue.object(object).parseErrorDetail)")
        }
        return object
    }
}

/// Foundation's AsyncBytes.lines omits empty lines on supported Apple SDKs.
/// Preserve SSE event separators and decode UTF-8 only after the full line.
struct DeepReadSSELines {
    private var bytes: [UInt8] = []
    private var skipLF = false

    mutating func consume(_ byte: UInt8) -> String? {
        if skipLF {
            skipLF = false
            if byte == 10 { return nil }
        }
        if byte == 13 || byte == 10 {
            skipLF = byte == 13
            let line = String(decoding: bytes, as: UTF8.self)
            bytes.removeAll(keepingCapacity: true)
            return line
        }
        bytes.append(byte)
        return nil
    }

    mutating func flush() -> String? {
        guard !bytes.isEmpty else { return nil }
        defer { bytes.removeAll(keepingCapacity: true) }
        return String(decoding: bytes, as: UTF8.self)
    }
}

/// SSE data fields belong to one event until an empty line; comments do not
/// represent model progress.
struct DeepReadSSEDecoder {
    private var name: String?
    private var data: [String] = []

    mutating func consume(_ rawLine: String) -> DeepReadSSEEvent? {
        let line = rawLine.hasSuffix("\r") ? String(rawLine.dropLast()) : rawLine
        if line.isEmpty { return flush() }
        if line.hasPrefix(":") { return nil }
        let fields = line.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
        var value = fields.count == 2 ? String(fields[1]) : ""
        if value.hasPrefix(" ") { value.removeFirst() }
        switch fields[0] {
        case "event": name = value
        case "data": data.append(value)
        default: break
        }
        return nil
    }

    mutating func flush() -> DeepReadSSEEvent? {
        defer { name = nil; data.removeAll(keepingCapacity: true) }
        guard !data.isEmpty else { return nil }
        return DeepReadSSEEvent(name: name, data: data.joined(separator: "\n"))
    }
}

/// Aggregate only the first requested choice, then use the existing complete
/// response parser so tool calls and output-limit reasons keep their semantics.
struct DeepReadChatStream {
    private var response: [String: JSONValue] = [:]
    private var message: [String: JSONValue] = ["role": .string("assistant")]
    private var tools: [Int: [String: JSONValue]] = [:]
    private var finishReason: String?

    mutating func consume(_ event: DeepReadSSEEvent) throws -> Int {
        if event.data == "[DONE]" { return 0 }
        let object = try event.jsonObject()
        for key in ["id", "model", "usage"] where object[key] != nil && object[key] != .null {
            response[key] = object[key]
        }
        guard let choice = object["choices"]?.arrayValue?.first(where: {
            ($0["index"]?.intValue ?? 0) == 0
        })?.objectValue else { return 0 }
        if let reason = choice["finish_reason"]?.stringValue { finishReason = reason }
        guard let delta = choice["delta"]?.objectValue else { return 0 }
        if let role = delta["role"]?.stringValue { message["role"] = .string(role) }
        var count = 0
        for key in ["content", "reasoning_content", "reasoning"] {
            if let text = delta[key]?.stringValue {
                message[key] = .string((message[key]?.stringValue ?? "") + text)
                count += text.count
            }
        }
        for call in delta["tool_calls"]?.arrayValue ?? [] {
            guard let index = call["index"]?.intValue else {
                throw DeepReadProviderError("OpenAI stream tool call is missing its index.")
            }
            var tool = tools[index] ?? ["index": .number(Double(index))]
            for key in ["id", "type"] {
                if let value = call[key] { tool[key] = value }
            }
            var function = tool["function"]?.objectValue ?? [:]
            for key in ["name", "arguments"] {
                if let text = call["function"]?[key]?.stringValue {
                    function[key] = .string((function[key]?.stringValue ?? "") + text)
                    if key == "arguments" { count += text.count }
                }
            }
            tool["function"] = .object(function)
            tools[index] = tool
        }
        return count
    }

    func finalResponse() throws -> JSONValue {
        guard let finishReason else {
            throw DeepReadProviderError("OpenAI stream ended before a finish reason was received.")
        }
        var completeMessage = message
        if !tools.isEmpty {
            completeMessage["tool_calls"] = .array(tools.keys.sorted().compactMap { tools[$0].map(JSONValue.object) })
        }
        var completeResponse = response
        completeResponse["choices"] = .array([.object([
            "index": .number(0), "message": .object(completeMessage), "finish_reason": .string(finishReason)
        ])])
        return .object(completeResponse)
    }
}

struct DeepReadResponsesStream {
    private var response: JSONValue?

    mutating func consume(_ event: DeepReadSSEEvent) throws -> Int {
        let object = try event.jsonObject()
        let type = object["type"]?.stringValue ?? event.name ?? ""
        switch type {
        case "response.completed", "response.incomplete", "response.failed":
            guard let value = object["response"], value.objectValue != nil else {
                throw DeepReadProviderError("OpenAI Responses stream terminal event is missing its response.")
            }
            response = value
            return 0
        case "response.output_text.delta", "response.reasoning_text.delta",
             "response.reasoning_summary_text.delta", "response.function_call_arguments.delta",
             "response.refusal.delta":
            return object["delta"]?.stringValue?.count ?? 0
        default:
            return 0
        }
    }

    func finalResponse() throws -> JSONValue {
        guard let response else {
            throw DeepReadProviderError("OpenAI Responses stream ended before a terminal response was received.")
        }
        return response
    }
}

struct DeepReadClaudeStream {
    private var response: [String: JSONValue] = [:]
    private var blocks: [Int: [String: JSONValue]] = [:]
    private var partialInputs: [Int: String] = [:]
    private var completed = false

    mutating func consume(_ event: DeepReadSSEEvent) throws -> Int {
        let object = try event.jsonObject()
        switch object["type"]?.stringValue ?? event.name {
        case "message_start":
            guard let message = object["message"]?.objectValue else {
                throw DeepReadProviderError("Claude stream message_start is missing its message.")
            }
            response = message
            for (index, block) in (message["content"]?.arrayValue ?? []).enumerated() {
                blocks[index] = block.objectValue
            }
        case "content_block_start":
            guard let index = object["index"]?.intValue, let block = object["content_block"]?.objectValue else {
                throw DeepReadProviderError("Claude stream content block is invalid.")
            }
            blocks[index] = block
            return (block["text"]?.stringValue?.count ?? 0) + (block["thinking"]?.stringValue?.count ?? 0)
        case "content_block_delta":
            guard let index = object["index"]?.intValue, var block = blocks[index],
                  let delta = object["delta"]?.objectValue else {
                throw DeepReadProviderError("Claude stream delta has no matching content block.")
            }
            var count = 0
            for key in ["text", "thinking", "signature"] {
                if let text = delta[key]?.stringValue {
                    block[key] = .string((block[key]?.stringValue ?? "") + text)
                    if key != "signature" { count += text.count }
                }
            }
            if let partial = delta["partial_json"]?.stringValue {
                partialInputs[index, default: ""] += partial
                count += partial.count
            }
            blocks[index] = block
            return count
        case "message_delta":
            for (key, value) in object["delta"]?.objectValue ?? [:] { response[key] = value }
            var usage = response["usage"]?.objectValue ?? [:]
            for (key, value) in object["usage"]?.objectValue ?? [:] { usage[key] = value }
            response["usage"] = .object(usage)
        case "message_stop":
            completed = true
        default:
            break
        }
        return 0
    }

    func finalResponse() throws -> JSONValue {
        guard completed, !response.isEmpty else {
            throw DeepReadProviderError("Claude stream ended before message_stop was received.")
        }
        var complete = response
        complete["content"] = .array(try blocks.keys.sorted().map { index in
            var block = blocks[index]!
            if let input = partialInputs[index], !input.isEmpty {
                guard let json = JSONValue.parse(input), json.objectValue != nil else {
                    throw DeepReadProviderError("Claude stream returned invalid tool input JSON.")
                }
                block["input"] = json
            }
            return .object(block)
        })
        return .object(complete)
    }
}
