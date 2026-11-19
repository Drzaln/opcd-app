package dev.opencode.mobile.data.net

import dev.opencode.mobile.data.model.Message
import dev.opencode.mobile.data.model.MessageTime
import dev.opencode.mobile.data.model.Part
import dev.opencode.mobile.data.model.PartTime
import dev.opencode.mobile.data.model.Tokens
import dev.opencode.mobile.data.model.ToolState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Turns opencode 2.x's fine-grained session events (`session.text.delta`, `session.tool.called`, …)
 * into the 1.x-shaped `message.updated` / `message.part.updated` patches the chat already applies,
 * so a reply streams in live instead of the app re-downloading the message list after every burst.
 *
 * Live parts get their own ids (`<message>:live:text:<n>`); the one reconciling fetch when the turn
 * ends replaces the whole message with the server's copy, so they never linger. Tool parts use the
 * call id, which is also what the stored message uses.
 *
 * Returns null for event types this class doesn't handle (the caller maps those as before), and an
 * empty list for events it handles by ignoring (usage counters, step markers).
 */
class V2LiveEvents(private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }) {

    private val messages = HashMap<String, Message>()
    private val tools = HashMap<String, Part>()

    fun map(type: String, created: Long?, data: JsonObject?): List<OcEvent>? {
        if (!type.startsWith("session.")) return null
        val d = data ?: return emptyList()
        val sessionId = d.str("sessionID").orEmpty()
        val messageId = d.str("assistantMessageID").orEmpty()
        val now = created ?: System.currentTimeMillis()
        return when (type) {
            "session.inbox.enqueued" -> {
                val item = d["item"] as? JsonObject
                val text = (item?.get("payload") as? JsonObject)?.str("text")
                val id = d.str("inboxID")
                if (item?.str("type") != "user" || id == null || text == null) emptyList()
                else listOf(
                    messageUpdated(Message(id = id, sessionID = sessionId, role = "user", time = MessageTime(created = now))),
                    partUpdated(Part(id = "${id}_text", sessionID = sessionId, messageID = id, type = "text", text = text)),
                )
            }
            "session.step.started" -> {
                if (messageId.isEmpty()) return emptyList()
                val model = d["model"] as? JsonObject
                val info = Message(
                    id = messageId,
                    sessionID = sessionId,
                    role = "assistant",
                    time = MessageTime(created = d.long("started") ?: now),
                    agent = d.str("agent"),
                    providerID = model?.str("providerID"),
                    modelID = model?.str("id"),
                )
                messages[messageId] = info
                listOf(messageUpdated(info))
            }
            "session.step.ended" -> {
                val info = messages.remove(messageId) ?: return emptyList()
                val tokens = d["tokens"]?.let { runCatching { json.decodeFromJsonElement<Tokens>(it) }.getOrNull() }
                listOf(messageUpdated(info.copy(
                    time = info.time?.copy(completed = now),
                    tokens = tokens ?: info.tokens,
                    cost = (d["cost"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: info.cost,
                    finish = d.str("finish") ?: info.finish,
                )))
            }
            "session.text.started", "session.reasoning.started" -> {
                if (messageId.isEmpty()) return emptyList()
                listOf(partUpdated(livePart(type, sessionId, messageId, d, text = "")))
            }
            "session.text.delta", "session.reasoning.delta" -> {
                val delta = d.str("delta")
                if (messageId.isEmpty() || delta.isNullOrEmpty()) return emptyList()
                listOf(partUpdated(livePart(type, sessionId, messageId, d, text = ""), delta))
            }
            "session.text.ended", "session.reasoning.ended" -> {
                val text = d.str("text")
                if (messageId.isEmpty() || text == null) return emptyList()
                listOf(partUpdated(livePart(type, sessionId, messageId, d, text = text)))
            }
            "session.tool.input.started" -> tool(d, sessionId, messageId) {
                it.copy(tool = d.str("name") ?: it.tool, state = (it.state ?: ToolState()).copy(status = "pending", time = PartTime(start = now)))
            }
            "session.tool.called" -> tool(d, sessionId, messageId) {
                val state = it.state ?: ToolState(time = PartTime(start = now))
                it.copy(tool = d.str("name") ?: it.tool, state = state.copy(status = "running", input = d["input"] as? JsonObject ?: state.input))
            }
            "session.tool.success" -> tool(d, sessionId, messageId, done = true) {
                val state = it.state ?: ToolState()
                it.copy(state = state.copy(
                    status = "completed",
                    output = contentText(d["content"]),
                    metadata = d["metadata"] as? JsonObject ?: state.metadata,
                    time = (state.time ?: PartTime(start = now)).copy(end = now),
                ))
            }
            "session.tool.failed" -> tool(d, sessionId, messageId, done = true) {
                val state = it.state ?: ToolState()
                val err = d["error"]
                val message = (err as? JsonObject)?.str("message") ?: (err as? JsonPrimitive)?.contentOrNull ?: err?.toString()
                it.copy(state = state.copy(
                    status = "error",
                    error = message ?: "Tool failed",
                    output = contentText(d["content"]),
                    time = (state.time ?: PartTime(start = now)).copy(end = now),
                ))
            }
            // Progress markers and counters: nothing to draw (the turn's final fetch carries totals).
            "session.step.first-token", "session.step.streamed", "session.usage.updated",
            "session.tool.input.delta", "session.tool.input.ended", "session.tool.progress",
            "session.inbox.delivered", "session.instructions.updated" -> emptyList()
            else -> null
        }
    }

    private fun livePart(type: String, sessionId: String, messageId: String, d: JsonObject, text: String): Part {
        val kind = if (type.startsWith("session.reasoning")) "reasoning" else "text"
        val ordinal = d.long("ordinal") ?: 0
        return Part(id = "$messageId:live:$kind:$ordinal", sessionID = sessionId, messageID = messageId, type = kind, text = text)
    }

    private inline fun tool(d: JsonObject, sessionId: String, messageId: String, done: Boolean = false, change: (Part) -> Part): List<OcEvent> {
        val callId = d.str("id") ?: return emptyList()
        if (messageId.isEmpty()) return emptyList()
        val base = tools[callId] ?: Part(id = callId, sessionID = sessionId, messageID = messageId, type = "tool", callID = callId)
        val part = change(base)
        if (done) tools.remove(callId) else tools[callId] = part
        return listOf(partUpdated(part))
    }

    private fun contentText(content: JsonElement?): String = (content as? JsonArray).orEmpty().mapNotNull { item ->
        val o = item as? JsonObject ?: return@mapNotNull null
        when (o.str("type")) {
            "text" -> o.str("text")
            "file" -> o.str("name") ?: o.str("uri")
            else -> null
        }
    }.joinToString("\n")

    private fun messageUpdated(info: Message) =
        OcEvent("message.updated", buildJsonObject { put("info", json.encodeToJsonElement(info)) })

    private fun partUpdated(part: Part, delta: String? = null) = OcEvent("message.part.updated", buildJsonObject {
        put("part", json.encodeToJsonElement(part))
        if (delta != null) put("delta", delta)
    })

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}
