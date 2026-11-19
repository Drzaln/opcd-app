package dev.opencode.mobile.data.net

import dev.opencode.mobile.data.model.Message
import dev.opencode.mobile.data.model.Part
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The event sequence opencode 2.3 sent for "read a.txt, then answer" (captured from a real run,
 * long fields trimmed): a user message, a step with one tool call, a step with streamed text.
 */
class V2LiveEventsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val sid = "ses_1"
    private val m1 = "msg_a1"
    private val m2 = "msg_a2"

    private val events = listOf(
        "session.inbox.enqueued" to """{"inboxID":"msg_u1","sessionID":"$sid","item":{"type":"user","payload":{"text":"read a.txt"},"delivery":"steer"}}""",
        "session.execution.started" to """{"sessionID":"$sid"}""",
        "session.inbox.delivered" to """{"sessionID":"$sid","inboxID":"msg_u1"}""",
        "session.step.started" to """{"sessionID":"$sid","agent":"build","model":{"id":"m","providerID":"p"},"assistantMessageID":"$m1","started":100}""",
        "session.step.first-token" to """{"sessionID":"$sid","assistantMessageID":"$m1"}""",
        "session.tool.input.started" to """{"sessionID":"$sid","assistantMessageID":"$m1","id":"call_1","name":"read"}""",
        "session.tool.input.ended" to """{"sessionID":"$sid","assistantMessageID":"$m1","id":"call_1","text":""}""",
        "session.tool.called" to """{"sessionID":"$sid","assistantMessageID":"$m1","id":"call_1","input":{"path":"a.txt"},"executed":false}""",
        "session.tool.success" to """{"sessionID":"$sid","assistantMessageID":"$m1","id":"call_1","content":[{"type":"text","text":"1: hello"}],"metadata":{"truncated":false}}""",
        "session.step.ended" to """{"sessionID":"$sid","assistantMessageID":"$m1","finish":"tool-calls","tokens":{"input":10,"output":2,"reasoning":1,"cache":{"read":0,"write":0}},"cost":0.03}""",
        "session.usage.updated" to """{"sessionID":"$sid","cost":0.03}""",
        "session.step.started" to """{"sessionID":"$sid","agent":"build","model":{"id":"m","providerID":"p"},"assistantMessageID":"$m2","started":200}""",
        "session.text.started" to """{"sessionID":"$sid","assistantMessageID":"$m2","ordinal":0}""",
        "session.text.delta" to """{"sessionID":"$sid","assistantMessageID":"$m2","ordinal":0,"delta":"It says "}""",
        "session.text.delta" to """{"sessionID":"$sid","assistantMessageID":"$m2","ordinal":0,"delta":"hello."}""",
        "session.text.ended" to """{"sessionID":"$sid","assistantMessageID":"$m2","ordinal":0,"text":"It says hello."}""",
        "session.step.ended" to """{"sessionID":"$sid","assistantMessageID":"$m2","finish":"stop"}""",
        "session.execution.succeeded" to """{"sessionID":"$sid"}""",
    )

    private fun run(): List<Pair<String, OcEvent?>> {
        val live = V2LiveEvents()
        return events.flatMap { (type, data) ->
            val out = live.map(type, 1000L, json.parseToJsonElement(data).jsonObject)
            if (out == null) listOf(type to null) else out.map { type to it }
        }
    }

    private fun OcEvent.part(): Part = json.decodeFromJsonElement((data as JsonObject)["part"]!!)
    private fun OcEvent.info(): Message = json.decodeFromJsonElement((data as JsonObject)["info"]!!)
    private fun OcEvent.delta(): String? = (data as JsonObject)["delta"]?.jsonPrimitive?.contentOrNull

    @Test
    fun executionEventsAreLeftToTheExistingMapping() {
        val out = run()
        assertNull(out.first { it.first == "session.execution.started" }.second)
        assertNull(out.first { it.first == "session.execution.succeeded" }.second)
        // Counters and markers produce nothing at all.
        assertTrue(out.none { it.first == "session.usage.updated" || it.first == "session.step.first-token" })
    }

    @Test
    fun userMessageShowsUpImmediately() {
        val out = run().filter { it.first == "session.inbox.enqueued" }.map { it.second!! }
        assertEquals("msg_u1", out[0].info().id)
        assertEquals("user", out[0].info().role)
        assertEquals("msg_u1_text", out[1].part().id) // same id as the stored message's text part
        assertEquals("read a.txt", out[1].part().text)
    }

    @Test
    fun toolCallProgressesWithItsNameAndOutput() {
        val parts = run().mapNotNull { it.second }.filter { it.type == "message.part.updated" }.map { it.part() }.filter { it.type == "tool" }
        assertEquals(listOf("pending", "running", "completed"), parts.map { it.state?.status })
        assertTrue(parts.all { it.id == "call_1" && it.messageID == m1 && it.tool == "read" })
        assertEquals("a.txt", parts[1].state?.input?.get("path")?.jsonPrimitive?.content)
        assertEquals("1: hello", parts[2].state?.output)
    }

    @Test
    fun textStreamsAsDeltasThenSettles() {
        val text = run().mapNotNull { it.second }.filter { it.type == "message.part.updated" }.filter { it.part().type == "text" && it.part().messageID == m2 }
        assertEquals(listOf(null, "It says ", "hello.", null), text.map { it.delta() })
        assertEquals("It says hello.", text.last().part().text)
        assertTrue(text.all { it.part().id == "$m2:live:text:0" })
    }

    @Test
    fun stepEndKeepsModelAndAddsUsage() {
        val infos = run().mapNotNull { it.second }.filter { it.type == "message.updated" }.map { it.info() }.filter { it.id == m1 }
        assertEquals(2, infos.size)
        assertEquals("p", infos[1].providerID)
        assertEquals("build", infos[1].agent)
        assertEquals("tool-calls", infos[1].finish)
        assertEquals(10L, infos[1].tokens?.input)
        assertEquals(1000L, infos[1].time?.completed)
    }
}
