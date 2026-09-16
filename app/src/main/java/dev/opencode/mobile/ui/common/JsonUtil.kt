package dev.opencode.mobile.ui.common

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

object JsonUtil {
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun pretty(value: JsonObject?): String {
        if (value == null) return ""
        return try {
            pretty.encodeToString(JsonObject.serializer(), value)
        } catch (t: Throwable) {
            value.toString()
        }
    }
}