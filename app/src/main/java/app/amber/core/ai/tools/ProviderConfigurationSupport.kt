package app.amber.core.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.amber.ai.provider.ProviderSetting
import app.amber.core.settings.secret.SecretDescriptor
import app.amber.core.settings.secret.SecretStore

internal fun providerConfigError(message: String, candidates: List<ProviderSetting>? = null): JsonObject = buildJsonObject {
    put("status", "failed")
    put("error", message)
    put("written", false)
    if (!candidates.isNullOrEmpty()) {
        put("candidates", buildJsonArray {
            candidates.forEach { p ->
                add(buildJsonObject {
                    put("id", p.id.toString())
                    put("name", p.name)
                    put("type", p.typeName())
                    put("enabled", p.enabled)
                })
            }
        })
    }
}

internal fun ProviderSetting.typeName(): String = when (this) {
    is ProviderSetting.OpenAI -> "openai"
    is ProviderSetting.Google -> "google"
    is ProviderSetting.Claude -> "claude"
}

internal fun SecretStore.hasStoredKey(provider: ProviderSetting): Boolean {
    val descriptor = SecretDescriptor("provider", provider.id.toString(), "apiKey")
    // 判定 = 密文条目存在 且 能解密出非空值（Keystore 失效 / 密文损坏时不误报已配置）
    if (has(descriptor) && !read(descriptor).isNullOrBlank()) return true
    // 旧明文 / 孤儿掩码兜底：settings 内存值非空即视为已配置（只输出布尔，不输出值）
    return provider.apiKeyValue().isNotBlank()
}

/** 统一取各 concrete provider 的 apiKey 字段（sealed 基类无此字段）。 */
internal fun ProviderSetting.apiKeyValue(): String = when (this) {
    is ProviderSetting.OpenAI -> apiKey
    is ProviderSetting.Google -> apiKey
    is ProviderSetting.Claude -> apiKey
}

internal fun providerConfigFailure(message: String, written: Boolean): JsonObject = buildJsonObject {
    put("status", "failed")
    put("error", message)
    put("written", written)
}

internal fun providerStringProp(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

internal fun providerBoolProp(description: String) = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}
