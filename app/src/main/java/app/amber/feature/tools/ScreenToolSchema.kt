package app.amber.feature.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun screenStringProp(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

internal fun screenNumberProp(description: String) = buildJsonObject {
    put("type", "number")
    put("description", description)
}

internal fun screenIntegerProp(description: String) = buildJsonObject {
    put("type", "integer")
    put("description", description)
}
