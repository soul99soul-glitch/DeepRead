import Foundation

// MARK: - HTTP 支撑

/// Kotlin 侧 `Exception(message)` 的对应错误类型：错误消息原样暴露给
/// 上层的 `LocalizedError.errorDescription`，保证失败文案与 KMP 版一致。
public struct DeepReadProviderError: LocalizedError, Sendable {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var errorDescription: String? { message }
}

enum DeepReadAIHTTP {
    /// 与 Ktor 一致的语义：2xx 视为成功。
    static func isSuccess(_ statusCode: Int) -> Bool {
        (200..<300).contains(statusCode)
    }

    /// Kotlin `hostOf(baseUrl)` 的直译：取 authority，去掉 userinfo、
    /// 端口与 query/fragment，统一小写。
    static func host(of baseURL: String) -> String {
        var value = baseURL
        if let schemeEnd = value.range(of: "://") {
            value = String(value[schemeEnd.upperBound...])
        } else {
            // Kotlin substringAfter 在找不到分隔符时返回原串。
        }
        var authority = value
        if let slash = value.firstIndex(of: "/") {
            authority = String(value[..<slash])
        }
        authority = authority
            .split(separator: "?", maxSplits: 1).first.map(String.init) ?? authority
        authority = authority
            .split(separator: "#", maxSplits: 1).first.map(String.init) ?? authority
        if let at = authority.lastIndex(of: "@") {
            authority = String(authority[authority.index(after: at)...])
        }
        if let colon = authority.firstIndex(of: ":") {
            authority = String(authority[..<colon])
        }
        return authority.lowercased()
    }

    /// 发送 JSON POST，返回 (statusCode, 响应体文本)。
    static func post(
        _ url: String,
        headers: [CustomHeader],
        body: String,
        timeout: TimeInterval = 600
    ) async throws -> (status: Int, body: String) {
        let request = try makeRequest(url, headers: headers, body: body, timeout: timeout)
        let (data, response) = try await urlSession.data(for: request)
        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? 0
        return (statusCode, String(decoding: data, as: UTF8.self))
    }

    /// A gateway may return ordinary JSON despite stream=true. Return that body
    /// to the caller's existing parser; SSE events are consumed as they arrive.
    static func streamPost(
        _ url: String,
        headers: [CustomHeader],
        body: String,
        failurePrefix: String,
        onEvent: (DeepReadSSEEvent) async throws -> Void
    ) async throws -> String? {
        let request = try makeRequest(url, headers: headers, body: body, timeout: 600)
        let (bytes, response) = try await urlSession.bytes(for: request)
        let http = response as? HTTPURLResponse
        let status = http?.statusCode ?? 0
        guard isSuccess(status) else {
            var data = Data()
            for try await byte in bytes { data.append(byte) }
            throw DeepReadProviderError("\(failurePrefix): \(status) \(String(decoding: data, as: UTF8.self))")
        }

        var decoder = DeepReadSSEDecoder()
        var isSSE: Bool? = http?.value(forHTTPHeaderField: "Content-Type")?
            .lowercased().contains("text/event-stream") == true ? true : nil
        var jsonLines: [String] = []
        func consumeLine(_ line: String) async throws {
            if isSSE == nil, !line.isBlank {
                isSSE = ["data:", "event:", "id:", "retry:", ":"].contains { line.hasPrefix($0) }
            }
            if isSSE == true {
                if let event = decoder.consume(line) { try await onEvent(event) }
            } else {
                jsonLines.append(line)
            }
        }
        var lines = DeepReadSSELines()
        for try await byte in bytes {
            if let line = lines.consume(byte) {
                try Task.checkCancellation()
                try await consumeLine(line)
            }
        }
        if let line = lines.flush() { try await consumeLine(line) }
        if isSSE == true {
            if let event = decoder.flush() { try await onEvent(event) }
            return nil
        }
        return jsonLines.joined(separator: "\n")
    }

    private static func makeRequest(
        _ url: String, headers: [CustomHeader], body: String, timeout: TimeInterval
    ) throws -> URLRequest {
        guard let requestURL = URL(string: url) else {
            throw DeepReadProviderError("Invalid request URL: \(url)")
        }
        var request = URLRequest(url: requestURL)
        request.httpMethod = "POST"
        request.timeoutInterval = timeout
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        for header in headers where !header.name.trimmingCharacters(in: .whitespaces).isEmpty {
            request.setValue(header.value, forHTTPHeaderField: header.name)
        }
        request.httpBody = Data(body.utf8)
        return request
    }

    /// 长生成响应可能长时间无新数据；放宽请求级空闲超时，资源级仍由
    /// 外层阶段超时（withTimeout）把关。
    static let urlSession: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 600
        configuration.timeoutIntervalForResource = 3_600
        configuration.waitsForConnectivity = false
        return URLSession(configuration: configuration)
    }()
}

// MARK: - 错误细节提取

extension JSONValue {
    /// Kotlin `JsonElement.parseErrorDetail()` 直译：在 error 对象里按
    /// error/detail/message/description 顺序递归找第一个错误字段；
    /// 标量取内容，找不到字段时序列化整个对象。
    var parseErrorDetail: String {
        switch self {
        case .object(let entries):
            for key in ["error", "detail", "message", "description"] {
                if let field = entries[key] {
                    return field.parseErrorDetail
                }
            }
            return jsonString
        case .array(let values):
            guard let first = values.first else { return "Unknown error: Empty JSON array" }
            return first.parseErrorDetail
        case .string(let value):
            return value
        case .bool(let value):
            return value ? "true" : "false"
        case .number(let value):
            return value == value.rounded() && abs(value) < 1e15
                ? String(Int64(value))
                : String(value)
        case .null:
            return "null"
        }
    }
}

// MARK: - OpenCode 端点头

enum OpenCodeRequestHeaders {
    static let sessionHeader = "x-opencode-session"
    static let defaultUserAgent = "AmberAgent/1.0"

    /// 仅当 URL authority 的 host 恰为 `opencode.ai`（大小写不敏感）时为真。
    static func isOpenCodeEndpoint(_ baseURL: String) -> Bool {
        DeepReadAIHTTP.host(of: baseURL) == "opencode.ai"
    }

    /// Kotlin `forGeneration`：OpenCode 端点补会话与 UA 头；其余端点仅
    /// 过滤空白头。用户自定义头按大小写不敏感保留。
    static func forGeneration(
        baseURL: String,
        messages: [UIMessage],
        customHeaders: [CustomHeader]
    ) -> [CustomHeader] {
        let headers = customHeaders.filter { !$0.name.isBlank }
        guard isOpenCodeEndpoint(baseURL) else { return headers }

        func lastHeader(_ name: String) -> CustomHeader? {
            headers.last { $0.name.caseInsensitiveCompare(name) == .orderedSame && !$0.value.isBlank }
        }
        let explicitSession = lastHeader(sessionHeader)
        let explicitUserAgent = lastHeader("User-Agent")
        let preserved = headers.filter { header in
            header.name.caseInsensitiveCompare(sessionHeader) != .orderedSame &&
                header.name.caseInsensitiveCompare("User-Agent") != .orderedSame
        }

        let sessionValue = explicitSession?.value
            ?? messages.first { $0.role == .user }?.id.uuidString
            ?? messages.first?.id.uuidString
            ?? UUID().uuidString

        return preserved
            + [CustomHeader(name: sessionHeader, value: sessionValue)]
            + [CustomHeader(name: "User-Agent", value: explicitUserAgent?.value ?? defaultUserAgent)]
    }
}

// MARK: - OpenAI 兼容请求头

enum DeepReadOpenAIHeaders {
    /// Kotlin `OpenAICompatUserAgents`：Coding Plan 端点的伪装 UA。
    static let opencodeUserAgent = "opencode/1.18.18"

    /// Kotlin `resolveOpenAIRequestHeaders`：Coding Plan 注入 OpenCode UA，
    /// 自定义头按大小写不敏感合并（后写优先）。
    static func resolve(
        authMode: OpenAIAuthMode,
        extraHeaders: [CustomHeader]
    ) -> [CustomHeader] {
        var merged: [String: (name: String, value: String)] = [:]
        func put(name: String, value: String) {
            let trimmed = name.trimmingCharacters(in: .whitespaces)
            guard !trimmed.isEmpty else { return }
            merged[trimmed.lowercased()] = (trimmed, value)
        }
        if authMode.isCodingPlan {
            put(name: "User-Agent", value: opencodeUserAgent)
        }
        extraHeaders.forEach { put(name: $0.name, value: $0.value) }
        return merged.values.map { entry in
            CustomHeader(
                name: entry.name.caseInsensitiveCompare("User-Agent") == .orderedSame
                    ? "User-Agent" : entry.name,
                value: entry.value
            )
        }
    }

    /// Kotlin `resolveAuthenticationHeaders`：MiMo 端点用 `api-key` 头，
    /// 其余用 `Authorization: Bearer`；Coding Plan UA 与额外头按大小写
    /// 不敏感合并（后写优先）。
    static func authenticationHeaders(
        for provider: ProviderSetting.OpenAI,
        extraHeaders: [CustomHeader] = []
    ) -> [CustomHeader] {
        let host = DeepReadAIHTTP.host(of: provider.baseUrl)
        let usesMimoApiKey = provider.brand == .mimo ||
            provider.authMode == .mimoCodingPlan ||
            (host.hasPrefix("token-plan-") && host.hasSuffix("xiaomimimo.com"))
        let authentication = CustomHeader(
            name: usesMimoApiKey ? "api-key" : "Authorization",
            value: usesMimoApiKey ? provider.apiKey : "Bearer \(provider.apiKey)"
        )
        return [authentication] + resolve(authMode: provider.authMode, extraHeaders: extraHeaders)
    }
}

extension String {
    var isBlank: Bool {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}
