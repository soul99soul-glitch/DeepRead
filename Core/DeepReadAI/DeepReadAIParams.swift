import Foundation

// MARK: - JSON 值

/// 请求体构建使用的轻量 JSON 值类型，替代 Kotlin 的 JsonElement。
/// 自定义 body 合并（`mergeCustomBody`）需要在顶层键上覆写，
/// 用值类型字典比 JSONSerialization 的 NSNumber 桥更可控。
/// 文本解析见 DeepReadJSONParser.swift 的 extension。
public enum JSONValue: Sendable, Hashable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    /// 对象键访问；非对象返回 nil（对齐 Kotlin `JsonObject[key]`）。
    public subscript(key: String) -> JSONValue? {
        guard case let .object(entries) = self else { return nil }
        return entries[key]
    }

    public var stringValue: String? {
        guard case let .string(value) = self else { return nil }
        return value
    }

    /// Kotlin `jsonPrimitive.intOrNull`：仅整数值可转换。
    public var intValue: Int? {
        guard case let .number(value) = self else { return nil }
        return Int(exactly: value)
    }

    public var objectValue: [String: JSONValue]? {
        guard case let .object(entries) = self else { return nil }
        return entries
    }

    public var arrayValue: [JSONValue]? {
        guard case let .array(values) = self else { return nil }
        return values
    }

    /// 序列化为 JSON 文本。字符串转义覆盖 JSON 规范要求的控制字符与引号。
    public var jsonString: String {
        switch self {
        case .null:
            return "null"
        case .bool(let value):
            return value ? "true" : "false"
        case .number(let value):
            if value == value.rounded() && abs(value) < 1e15 {
                return String(Int64(value))
            }
            return String(value)
        case .string(let value):
            return "\"\(Self.escaped(value))\""
        case .array(let values):
            return "[\(values.map(\.jsonString).joined(separator: ","))]"
        case .object(let entries):
            let body = entries
                .sorted(by: { $0.key < $1.key })
                .map { "\"\(Self.escaped($0.key))\":\($0.value.jsonString)" }
                .joined(separator: ",")
            return "{\(body)}"
        }
    }

    static func escaped(_ value: String) -> String {
        var result = ""
        for scalar in value.unicodeScalars {
            switch scalar {
            case "\"": result += "\\\""
            case "\\": result += "\\\\"
            case "\n": result += "\\n"
            case "\r": result += "\\r"
            case "\t": result += "\\t"
            default:
                if scalar.value < 0x20 {
                    result += String(format: "\\u%04x", scalar.value)
                } else {
                    result.unicodeScalars.append(scalar)
                }
            }
        }
        return result
    }
}

// MARK: - 自定义头与自定义请求体

/// Kotlin `CustomHeader` 切片。
public struct CustomHeader: Sendable, Codable, Equatable {
    public var name: String
    public var value: String

    public init(name: String, value: String) {
        self.name = name
        self.value = value
    }
}

/// Kotlin `CustomBody` 切片：顶层请求体键覆写。
public struct CustomBody: Sendable, Equatable {
    public var key: String
    public var value: JSONValue

    public init(key: String, value: JSONValue) {
        self.key = key
        self.value = value
    }
}

// MARK: - 模型

/// Kotlin `ModelType` 切片。
public enum ModelType: String, Sendable, Codable {
    case chat, image, embedding
}

/// Kotlin `Modality` 切片。
public enum Modality: String, Sendable, Codable {
    case text, image, audio, video
}

/// Kotlin `ModelAbility` 切片。
public enum ModelAbility: String, Sendable, Codable {
    case tool, reasoning
}

/// Kotlin `BuiltInTools` 切片：服务商内置工具标记。DeepRead 恒传空集。
public enum BuiltInTools: String, Sendable, Codable, Hashable {
    case search
    case urlContext
    case imageGeneration
}

/// Kotlin `Model` 的纯 Swift 切片。保持与 Kotlin 导出一致的参数标签，
/// 让既有构造点零改动迁移。
public struct Model: Sendable {
    public var modelId: String
    public var displayName: String
    public var id: UUID
    public var type: ModelType
    public var customHeaders: [CustomHeader]
    public var customBodies: [CustomBody]
    public var inputModalities: [Modality]
    public var outputModalities: [Modality]
    public var abilities: [ModelAbility]
    public var tools: Set<BuiltInTools>
    public var contextWindowTokens: Int?
    public var providerOverwrite: ProviderSetting?

    public init(
        modelId: String = "",
        displayName: String = "",
        id: UUID = UUID(),
        type: ModelType = .chat,
        customHeaders: [CustomHeader] = [],
        customBodies: [CustomBody] = [],
        inputModalities: [Modality] = [.text],
        outputModalities: [Modality] = [.text],
        abilities: [ModelAbility] = [],
        tools: Set<BuiltInTools> = [],
        contextWindowTokens: Int? = nil,
        providerOverwrite: ProviderSetting? = nil
    ) {
        self.modelId = modelId
        self.displayName = displayName
        self.id = id
        self.type = type
        self.customHeaders = customHeaders
        self.customBodies = customBodies
        self.inputModalities = inputModalities
        self.outputModalities = outputModalities
        self.abilities = abilities
        self.tools = tools
        self.contextWindowTokens = contextWindowTokens
        self.providerOverwrite = providerOverwrite
    }
}

/// Kotlin `BalanceOption` 切片。DeepRead 不查余额，仅为对齐字段保留。
public struct BalanceOption: Sendable, Codable, Equatable {
    public var enabled: Bool
    public var apiPath: String
    public var resultPath: String

    public init(enabled: Bool = false, apiPath: String = "/credits", resultPath: String = "data.total_usage") {
        self.enabled = enabled
        self.apiPath = apiPath
        self.resultPath = resultPath
    }
}

/// Kotlin `ProviderSetting` 密封类基类的纯 Swift 切片（OpenAI/Claude 具体
/// case 见 DeepReadProviderSettings.swift）。class 层级保持
/// `provider as? ProviderSetting.OpenAI` 的下游分发语法。
///
/// 并发说明：实例在设置保存时构造，之后被运行管线跨 actor 只读使用；
/// 标 `@unchecked Sendable` 与 Kotlin data class 的实际使用方式一致。
public class ProviderSetting: @unchecked Sendable {
    public let id: UUID
    public var enabled: Bool
    public var name: String
    public var models: [Model]
    public var balanceOption: BalanceOption
    public let builtIn: Bool
    public let descriptionText: String?
    public let shortDescriptionText: String?

    public init(
        id: UUID = UUID(),
        enabled: Bool = true,
        name: String,
        models: [Model] = [],
        balanceOption: BalanceOption = BalanceOption(),
        builtIn: Bool = false,
        descriptionText: String? = nil,
        shortDescriptionText: String? = nil
    ) {
        self.id = id
        self.enabled = enabled
        self.name = name
        self.models = models
        self.balanceOption = balanceOption
        self.builtIn = builtIn
        self.descriptionText = descriptionText
        self.shortDescriptionText = shortDescriptionText
    }
}

// MARK: - 推理等级

/// Kotlin `ReasoningLevel` 切片。DeepRead 管线恒传 `.off`；
/// 其余档位保留以便未来开放深读推理。
public enum ReasoningLevel: String, Sendable, Codable, CaseIterable {
    case off, auto, low, medium, high, xhigh, max

    public var budgetTokens: Int {
        switch self {
        case .off: 0
        case .auto: -1
        case .low: 1_000
        case .medium: 2_000
        case .high: 8_000
        case .xhigh: 16_000
        case .max: 32_000
        }
    }

    public var effort: String {
        switch self {
        case .off: "none"
        case .auto: "auto"
        case .low: "low"
        case .medium: "medium"
        case .high: "high"
        case .xhigh: "xhigh"
        case .max: "max"
        }
    }

    /// Kotlin `isEnabled`：非 OFF 即启用。
    public var isEnabled: Bool { self != .off }
}

// MARK: - 文本生成参数

/// Kotlin `TextGenerationParams` 切片。移除了 DeepRead 永不传的
/// `tools` 字段（DeepRead 管线不注册工具，工具调用出现即视为失败）。
public struct TextGenerationParams: @unchecked Sendable {
    public var model: Model
    public var temperature: Double?
    public var topP: Double?
    public var maxTokens: Int?
    public var reasoningLevel: ReasoningLevel
    public var customHeaders: [CustomHeader]
    public var customBody: [CustomBody]

    public init(
        model: Model,
        temperature: Double? = nil,
        topP: Double? = nil,
        maxTokens: Int? = nil,
        reasoningLevel: ReasoningLevel = .off,
        customHeaders: [CustomHeader] = [],
        customBody: [CustomBody] = []
    ) {
        self.model = model
        self.temperature = temperature
        self.topP = topP
        self.maxTokens = maxTokens
        self.reasoningLevel = reasoningLevel
        self.customHeaders = customHeaders
        self.customBody = customBody
    }
}
