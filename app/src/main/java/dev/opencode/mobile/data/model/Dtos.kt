package dev.opencode.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class FileDiff(
    val file: String = "",
    val before: String = "",
    val after: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
)

@Serializable
data class MessageTime(
    val created: Long = 0,
    val completed: Long? = null,
)

@Serializable
data class PartTime(
    val start: Long = 0,
    val end: Long? = null,
)

@Serializable
data class Cache(
    val read: Long = 0,
    val write: Long = 0,
)

@Serializable
data class Tokens(
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cache: Cache = Cache(),
)

@Serializable
data class MessagePath(
    val cwd: String = "",
    val root: String = "",
)

@Serializable
data class MessageSummary(
    val title: String? = null,
    val body: String? = null,
    val diffs: List<FileDiff> = emptyList(),
)

@Serializable
data class MessageModel(
    val providerID: String = "",
    val modelID: String = "",
)

@Serializable
data class Message(
    val id: String = "",
    val sessionID: String = "",
    val role: String = "",
    val time: MessageTime? = null,
    val parentID: String? = null,
    val modelID: String? = null,
    val providerID: String? = null,
    val mode: String? = null,
    val path: MessagePath? = null,
    val summary: MessageSummary? = null,
    val agent: String? = null,
    val model: MessageModel? = null,
    val error: JsonObject? = null,
    val cost: Double? = null,
    val tokens: Tokens? = null,
    val finish: String? = null,
)

@Serializable
data class ToolState(
    val status: String = "",
    val input: JsonObject? = null,
    val raw: String = "",
    val output: String = "",
    val error: String = "",
    val title: String = "",
    val metadata: JsonObject? = null,
    val time: PartTime? = null,
    val attachments: List<Part>? = null,
)

@Serializable
data class Part(
    val id: String = "",
    val sessionID: String = "",
    val messageID: String = "",
    val type: String = "",
    val text: String = "",
    val synthetic: Boolean? = null,
    val ignored: Boolean? = null,
    val time: PartTime? = null,
    val mime: String = "",
    val filename: String? = null,
    val url: String = "",
    val source: JsonObject? = null,
    val callID: String = "",
    val tool: String = "",
    val state: ToolState? = null,
    val prompt: String = "",
    val description: String = "",
    val agent: String = "",
    val snapshot: String = "",
    val hash: String = "",
    val files: List<String> = emptyList(),
    val reason: String = "",
    val cost: Double? = null,
    val tokens: Tokens? = null,
    val attempt: Int? = null,
    val error: JsonObject? = null,
    val auto: Boolean? = null,
    val name: String = "",
    val metadata: JsonObject? = null,
)

@Serializable
data class MessageData(
    val info: Message,
    val parts: List<Part> = emptyList(),
)

@Serializable
data class SessionTime(
    val created: Long = 0,
    val updated: Long = 0,
    val compacting: Long? = null,
)

@Serializable
data class SessionSummary(
    val additions: Int = 0,
    val deletions: Int = 0,
    val files: Int = 0,
    val diffs: List<FileDiff> = emptyList(),
)

@Serializable
data class Session(
    val id: String = "",
    val projectID: String = "",
    val directory: String = "",
    val parentID: String? = null,
    val summary: SessionSummary? = null,
    val title: String = "",
    val version: String = "",
    val time: SessionTime? = null,
    val share: JsonObject? = null,
    val cost: Double? = null,
    val tokens: Tokens? = null,
)

@Serializable
data class ProjectTime(
    val created: Long = 0,
    val initialized: Long? = null,
)

@Serializable
data class Project(
    val id: String = "",
    val worktree: String = "",
    val vcs: String? = null,
    val vcsDir: String? = null,
    val time: ProjectTime? = null,
)

@Serializable
data class FileNode(
    val name: String = "",
    val path: String = "",
    val absolute: String = "",
    val type: String = "",
    val ignored: Boolean = false,
)

@Serializable
data class FileContent(
    val type: String = "text",
    val content: String = "",
    val encoding: String? = null,
    val mimeType: String? = null,
)

@Serializable
data class Todo(
    val id: String = "",
    val content: String = "",
    val status: String = "",
    val priority: String = "",
)

@Serializable
data class SessionStatus(
    val type: String = "idle",
    val attempt: Int? = null,
    val message: String? = null,
    val next: Long? = null,
)

@Serializable
data class FileStatus(
    val path: String = "",
    val added: Int = 0,
    val removed: Int = 0,
    val status: String = "",
)

@Serializable
data class Health(
    val healthy: Boolean = false,
    val version: String = "",
)

@Serializable
data class PartInput(
    val type: String = "text",
    val text: String = "",
    val synthetic: Boolean? = null,
)

@Serializable
data class ModelRef(
    val providerID: String = "",
    val modelID: String = "",
)

@Serializable
data class SendMessageBody(
    val messageID: String? = null,
    val model: ModelRef? = null,
    val agent: String? = null,
    val noReply: Boolean? = null,
    val system: String? = null,
    val parts: List<PartInput> = emptyList(),
)

@Serializable
data class CreateSessionBody(
    val parentID: String? = null,
    val title: String? = null,
)

@Serializable
data class SessionUpdateBody(
    val title: String? = null,
)

@Serializable
data class Command(
    val name: String = "",
    val description: String? = null,
    val agent: String? = null,
    val model: String? = null,
    val template: String = "",
    val subtask: Boolean = false,
)

@Serializable
data class CommandBody(
    val messageID: String? = null,
    val agent: String? = null,
    val model: String? = null,
    val command: String,
    val arguments: String = "",
)

@Serializable
data class Agent(
    val name: String = "",
    val description: String? = null,
    val mode: String = "primary",
    val builtIn: Boolean = false,
    val color: String? = null,
)

@Serializable
data class ModelInfo(
    val id: String = "",
    val name: String = "",
    val limit: ModelLimit? = null,
)

@Serializable
data class ModelLimit(
    val context: Long = 0,
    val output: Long = 0,
)

@Serializable
data class Provider(
    val id: String = "",
    val name: String = "",
    val source: String = "",
    val models: Map<String, ModelInfo> = emptyMap(),
)

@Serializable
data class ProviderList(
    val all: List<Provider> = emptyList(),
    val default: Map<String, String> = emptyMap(),
    val connected: List<String> = emptyList(),
)
