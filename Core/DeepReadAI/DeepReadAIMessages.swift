import Foundation

// MARK: - 消息角色

/// Kotlin `MessageRole` 的纯 Swift 切片。DeepRead 只产生 system/user 消息，
/// 解析响应时可能出现 assistant；tool 保留以完整对齐 Kotlin 枚举。
public enum MessageRole: String, Sendable, Codable {
    case system, user, assistant, tool

    /// Kotlin `MessageRole.valueOf(role.uppercase())` 的容错解析。
    public init(parsing raw: String?) {
        self = MessageRole(rawValue: (raw ?? "").lowercased()) ?? .assistant
    }
}

// MARK: - 消息部件

/// Kotlin `UIMessagePart` 密封类的纯 Swift 切片。DeepRead 只消费
/// Text（正文）、Tool（检测未完成的工具调用）、Reasoning（思考模型输出）。
/// 用 class 层级保持 `part as? UIMessagePart.Text` 的判定语法与 Kotlin 一致。
public class UIMessagePart: @unchecked Sendable {
    public init() {}
}

public extension UIMessagePart {
    /// 纯文本内容块。
    final class Text: UIMessagePart, Equatable, @unchecked Sendable {
        public let text: String

        public init(text: String) {
            self.text = text
            super.init()
        }

        public static func == (lhs: Text, rhs: Text) -> Bool { lhs.text == rhs.text }
    }

    /// 思考模型返回的推理内容（如 reasoning_content）。DeepRead 不展示，
    /// 但解析层需要把它和正文分开，避免混入正文。
    final class Reasoning: UIMessagePart, @unchecked Sendable {
        public let reasoning: String

        public init(reasoning: String) {
            self.reasoning = reasoning
            super.init()
        }
    }

    /// 工具调用块。DeepRead 管线不注册工具；出现未产生输出的工具调用
    /// 意味着服务商忽略了"只输出 JSON"的约束，运行层据此判定失败。
    final class Tool: UIMessagePart, @unchecked Sendable {
        public let toolCallId: String
        public let toolName: String
        public let input: String
        public var output: [UIMessagePart]
        public var approvalState: ToolApprovalState
        public let streamIndex: Int?

        public init(
            toolCallId: String,
            toolName: String,
            input: String,
            output: [UIMessagePart] = [],
            approvalState: ToolApprovalState = .auto,
            streamIndex: Int? = nil
        ) {
            self.toolCallId = toolCallId
            self.toolName = toolName
            self.input = input
            self.output = output
            self.approvalState = approvalState
            self.streamIndex = streamIndex
            super.init()
        }

        /// Kotlin `isExecuted`：已有输出即视为已执行。
        public var isExecuted: Bool { !output.isEmpty }
    }
}

/// Kotlin `ToolApprovalState` 切片。DeepRead 不走审批流，仅为对齐语义保留。
public enum ToolApprovalState: Sendable, Equatable {
    case auto
    case pending
    case approved
    case denied(reason: String)
    case answered(answer: String)
}

// MARK: - 消息

/// Kotlin `UIMessage` 的纯 Swift 切片：只保留 DeepRead 读写的字段
/// （id / role / parts）。Kotlin 版的 annotations、usage、translation、
/// 本地时间戳等 DeepRead 从不读取，直接省略。
public struct UIMessage: Sendable, Equatable {
    public var id: UUID
    public var role: MessageRole
    public var parts: [UIMessagePart]

    public init(id: UUID = UUID(), role: MessageRole, parts: [UIMessagePart]) {
        self.id = id
        self.role = role
        self.parts = parts
    }

    /// Kotlin `UIMessage.system(prompt:)`。
    public static func system(prompt: String) -> UIMessage {
        UIMessage(role: .system, parts: [UIMessagePart.Text(text: prompt)])
    }

    /// Kotlin `UIMessage.user(prompt:)`。
    public static func user(prompt: String) -> UIMessage {
        UIMessage(role: .user, parts: [UIMessagePart.Text(text: prompt)])
    }

    /// Kotlin `UIMessage.assistant(prompt:)`。
    public static func assistant(prompt: String) -> UIMessage {
        UIMessage(role: .assistant, parts: [UIMessagePart.Text(text: prompt)])
    }

    /// Kotlin `UIMessage.toText()`：拼接全部 Text 部件，换行分隔。
    public func toText() -> String {
        parts.compactMap { ($0 as? UIMessagePart.Text)?.text }
            .joined(separator: "\n")
    }

    public static func == (lhs: UIMessage, rhs: UIMessage) -> Bool {
        guard lhs.id == rhs.id, lhs.role == rhs.role, lhs.parts.count == rhs.parts.count else {
            return false
        }
        for (l, r) in zip(lhs.parts, rhs.parts) {
            switch (l, r) {
            case let (lt as UIMessagePart.Text, rt as UIMessagePart.Text):
                guard lt == rt else { return false }
            case let (lt as UIMessagePart.Reasoning, rt as UIMessagePart.Reasoning):
                guard lt.reasoning == rt.reasoning else { return false }
            case let (lt as UIMessagePart.Tool, rt as UIMessagePart.Tool):
                guard lt.toolCallId == rt.toolCallId, lt.toolName == rt.toolName,
                      lt.input == rt.input, lt.output.count == rt.output.count else { return false }
            default:
                return false
            }
        }
        return true
    }
}

// MARK: - 用量与响应块

/// Kotlin `TokenUsage` 切片。
public struct TokenUsage: Sendable, Equatable {
    public var promptTokens: Int
    public var completionTokens: Int
    public var cachedTokens: Int
    public var totalTokens: Int
    public var generationDurationMs: Int

    public init(
        promptTokens: Int = 0,
        completionTokens: Int = 0,
        cachedTokens: Int = 0,
        totalTokens: Int = 0,
        generationDurationMs: Int = 0
    ) {
        self.promptTokens = promptTokens
        self.completionTokens = completionTokens
        self.cachedTokens = cachedTokens
        self.totalTokens = totalTokens
        self.generationDurationMs = generationDurationMs
    }
}

/// Kotlin `UIMessageChoice` 切片。
public struct UIMessageChoice: Sendable {
    public let index: Int
    public let delta: UIMessage?
    public let message: UIMessage?
    public let finishReason: String?

    public init(index: Int, delta: UIMessage?, message: UIMessage?, finishReason: String?) {
        self.index = index
        self.delta = delta
        self.message = message
        self.finishReason = finishReason
    }
}

/// Kotlin `MessageChunk` 切片：非流式生成返回的完整响应块。
public struct MessageChunk: Sendable {
    public let id: String
    public let model: String
    public let choices: [UIMessageChoice]
    public let usage: TokenUsage?

    public init(id: String, model: String, choices: [UIMessageChoice], usage: TokenUsage? = nil) {
        self.id = id
        self.model = model
        self.choices = choices
        self.usage = usage
    }
}
