package dev.opencode.mobile.data.net

import dev.opencode.mobile.data.model.Agent
import dev.opencode.mobile.data.model.Cache
import dev.opencode.mobile.data.model.Command
import dev.opencode.mobile.data.model.CommandBody
import dev.opencode.mobile.data.model.CreateSessionBody
import dev.opencode.mobile.data.model.FileContent
import dev.opencode.mobile.data.model.FileDiff
import dev.opencode.mobile.data.model.FileNode
import dev.opencode.mobile.data.model.FileStatus
import dev.opencode.mobile.data.model.FindMatch
import dev.opencode.mobile.data.model.ForkBody
import dev.opencode.mobile.data.model.Health
import dev.opencode.mobile.data.model.Message
import dev.opencode.mobile.data.model.MessageData
import dev.opencode.mobile.data.model.MessageTime
import dev.opencode.mobile.data.model.ModelInfo
import dev.opencode.mobile.data.model.ModelLimit
import dev.opencode.mobile.data.model.ModelRef
import dev.opencode.mobile.data.model.ModelVariant
import dev.opencode.mobile.data.model.Part
import dev.opencode.mobile.data.model.PartTime
import dev.opencode.mobile.data.model.PermissionReplyBody
import dev.opencode.mobile.data.model.PermissionReplyV2Body
import dev.opencode.mobile.data.model.PermissionRequest
import dev.opencode.mobile.data.model.Project
import dev.opencode.mobile.data.model.ProjectTime
import dev.opencode.mobile.data.model.Provider
import dev.opencode.mobile.data.model.ProviderList
import dev.opencode.mobile.data.model.PtyCreateBody
import dev.opencode.mobile.data.model.PtyInfo
import dev.opencode.mobile.data.model.PtyShell
import dev.opencode.mobile.data.model.PtyUpdateBody
import dev.opencode.mobile.data.model.QuestionInfo
import dev.opencode.mobile.data.model.QuestionOption
import dev.opencode.mobile.data.model.QuestionReplyBody
import dev.opencode.mobile.data.model.QuestionRequest
import dev.opencode.mobile.data.model.RevertBody
import dev.opencode.mobile.data.model.SendMessageBody
import dev.opencode.mobile.data.model.Session
import dev.opencode.mobile.data.model.SessionRevert
import dev.opencode.mobile.data.model.SessionStatus
import dev.opencode.mobile.data.model.SessionTime
import dev.opencode.mobile.data.model.SessionUpdateBody
import dev.opencode.mobile.data.model.SummarizeBody
import dev.opencode.mobile.data.model.Todo
import dev.opencode.mobile.data.model.Tokens
import dev.opencode.mobile.data.model.VcsInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Response
import java.net.URLEncoder

/** Which opencode HTTP API generation a server speaks. */
enum class Protocol { V1, V2 }

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull
private fun JsonElement?.dbl(): Double? = (this as? JsonPrimitive)?.doubleOrNull
private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.let { if (it is JsonNull) null else it.contentOrNull?.toBooleanStrictOrNull() }
private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject
private fun JsonObject.field(name: String): JsonElement? = this[name]

/**
 * App-facing opencode client that speaks either the V1 or V2 HTTP API. Every method returns the
 * app's internal V1-shaped model; V2 payloads are mapped here so screens/ViewModels are agnostic.
 *
 * The protocol is detected once per server instance (see [protocol]) by probing `/api/info`.
 */
class OpenCodeApi(
    private val server: ServerConfig,
    private val v1: OpenCodeV1Api,
    private val v2: OpenCodeV2Api,
    private val rawClient: OkHttpClient,
) {

    @Volatile private var resolved: Protocol? = null
    private val detectLock = Mutex()

    suspend fun protocol(): Protocol {
        resolved?.let { return it }
        return detectLock.withLock {
            resolved ?: detect().also { resolved = it }
        }
    }

    private suspend fun detect(): Protocol = withContext(Dispatchers.IO) {
        val base = baseUrl()
        val v2probe = runCatching {
            rawClient.newCall(Request.Builder().url(base + "api/info").build()).execute().use { r ->
                when {
                    r.code in 200..299 -> r.body?.string().orEmpty().contains("\"version\"")
                    r.code == 401 || r.code == 403 || r.code == 400 -> true
                    else -> false
                }
            }
        }.getOrDefault(false)
        if (v2probe) Protocol.V2 else Protocol.V1
    }

    private fun baseUrl(): String = server.baseUrl.trimEnd('/') + "/"

    // ---------------- health / projects / location ----------------

    suspend fun health(): Health = when (protocol()) {
        Protocol.V1 -> v1.health()
        Protocol.V2 -> {
            val info = v2.info().asJsonObject()
            Health(healthy = true, version = info.field("version").str().orEmpty())
        }
    }

    suspend fun projects(): List<Project> = when (protocol()) {
        Protocol.V1 -> v1.projects()
        Protocol.V2 -> v2.projects().arrayOrData().mapNotNull { e -> e.asObjectOrNull()?.let { mapProject(it) } }
    }

    suspend fun currentProject(directory: String?): Project = when (protocol()) {
        Protocol.V1 -> v1.currentProject(directory)
        Protocol.V2 -> {
            val loc = v2.location(directory).asJsonObject()
            val project = loc.field("project")?.asObjectOrNull() ?: return Project()
            Project(
                id = project.field("id").str().orEmpty(),
                worktree = project.field("directory").str() ?: project.field("canonical").str().orEmpty(),
            )
        }
    }

    suspend fun currentPath(directory: String?): JsonObject = when (protocol()) {
        Protocol.V1 -> v1.currentPath(directory)
        Protocol.V2 -> buildJsonObject { put("directory", v2.location(directory).asJsonObject().field("directory").str().orEmpty()) }
    }

    suspend fun vcs(directory: String?): VcsInfo = when (protocol()) {
        Protocol.V1 -> v1.vcs(directory)
        Protocol.V2 -> {
            val data = v2.vcs(directory).dataOf()
            val branch = data?.field("branch")?.asObjectOrNull()
            VcsInfo(
                branch = branch?.field("current").str(),
                defaultBranch = branch?.field("default").str(),
            )
        }
    }

    // ---------------- sessions ----------------

    suspend fun sessions(directory: String?): List<Session> = when (protocol()) {
        Protocol.V1 -> v1.sessions(directory)
        Protocol.V2 -> v2.sessions(directory).arrayData().mapNotNull { it.asObjectOrNull()?.let { o -> mapSession(o) } }
    }

    suspend fun createSession(body: CreateSessionBody, directory: String?): Session = when (protocol()) {
        Protocol.V1 -> v1.createSession(body, directory)
        Protocol.V2 -> {
            val payload = buildJsonObject {
                body.parentID?.let { put("parentID", it) }
                body.title?.let { put("title", it) }
                if (directory != null) put("location", buildJsonObject { put("directory", directory) })
            }
            mapSession(v2.createSession(payload).dataObject())
        }
    }

    suspend fun session(id: String, directory: String? = null): Session = when (protocol()) {
        Protocol.V1 -> v1.session(id, directory)
        Protocol.V2 -> mapSession(v2.session(id, directory).dataObject())
    }

    suspend fun updateSession(id: String, body: SessionUpdateBody, directory: String?): Session = when (protocol()) {
        Protocol.V1 -> v1.updateSession(id, body, directory)
        Protocol.V2 -> {
            val payload = buildJsonObject { body.title?.let { put("title", it) } }
            mapSession(v2.updateSession(id, payload, directory).dataObject())
        }
    }

    suspend fun deleteSession(id: String, directory: String?): Boolean = when (protocol()) {
        Protocol.V1 -> v1.deleteSession(id, directory)
        Protocol.V2 -> v2.deleteSession(id, directory).isSuccessful
    }

    suspend fun sessionStatus(directory: String?): Map<String, SessionStatus> = when (protocol()) {
        Protocol.V1 -> v1.sessionStatus(directory)
        Protocol.V2 -> {
            val data = v2.sessionActive().asJsonObject().field("data")?.asObjectOrNull() ?: return emptyMap()
            data.mapValues { (_, value) ->
                val type = value.asObjectOrNull()?.field("type").str()
                SessionStatus(type = if (type == "running") "busy" else "idle")
            }
        }
    }

    suspend fun forkSession(id: String, body: ForkBody = ForkBody(), directory: String?): Session = when (protocol()) {
        Protocol.V1 -> v1.forkSession(id, body, directory)
        Protocol.V2 -> {
            val payload = buildJsonObject { body.messageID?.let { put("before", it) } }
            mapSession(v2.fork(id, payload).dataObject())
        }
    }

    suspend fun revertSession(id: String, body: RevertBody, directory: String?): Session = when (protocol()) {
        Protocol.V1 -> v1.revertSession(id, body, directory)
        Protocol.V2 -> {
            runCatching {
                v2.revertStage(id, buildJsonObject { put("messageID", body.messageID) })
                v2.revertCommit(id)
            }
            session(id, directory)
        }
    }

    suspend fun unrevertSession(id: String, directory: String?): Session = when (protocol()) {
        Protocol.V1 -> v1.unrevertSession(id, directory)
        Protocol.V2 -> {
            runCatching { v2.revertClear(id) }
            session(id, directory)
        }
    }

    suspend fun summarizeSession(id: String, body: SummarizeBody, directory: String?): Boolean = when (protocol()) {
        Protocol.V1 -> v1.summarizeSession(id, body, directory)
        Protocol.V2 -> runCatching { v2.compact(id, buildJsonObject { }); true }.getOrDefault(false)
    }

    suspend fun shareSession(id: String, directory: String?): Session = when (protocol()) {
        Protocol.V1 -> v1.shareSession(id, directory)
        Protocol.V2 -> session(id, directory)
    }

    suspend fun unshareSession(id: String, directory: String?): Session = when (protocol()) {
        Protocol.V1 -> v1.unshareSession(id, directory)
        Protocol.V2 -> session(id, directory)
    }

    suspend fun deleteMessage(id: String, messageID: String, directory: String?): Boolean = when (protocol()) {
        Protocol.V1 -> v1.deleteMessage(id, messageID, directory)
        Protocol.V2 -> false
    }

    // ---------------- messages ----------------

    suspend fun messages(id: String, limit: Int?, directory: String?): JsonArray = when (protocol()) {
        Protocol.V1 -> v1.messages(id, limit, directory)
        Protocol.V2 -> {
            val body = v2.messages(id, limit)
            buildJsonArray {
                // V2 returns newest-first; the app expects oldest-first (ascending).
                body.arrayData().mapNotNull { m -> m.asObjectOrNull()?.let { mapMessage(it, id) } }.asReversed().forEach { add(json.encodeToJsonElement(MessageData.serializer(), it)) }
            }
        }
    }

    suspend fun messagesPage(id: String, limit: Int, before: String?, directory: String?): Response<JsonArray> = when (protocol()) {
        Protocol.V1 -> v1.messagesPage(id, limit, before, directory)
        Protocol.V2 -> {
            val body = v2.messages(id, limit, before).asJsonObject()
            val list = body.field("data").arr() ?: JsonArray(emptyList())
            val mapped = list.mapNotNull { m -> m.asObjectOrNull()?.let { mapMessage(it, id) } }.asReversed()
            val array = buildJsonArray { mapped.forEach { add(json.encodeToJsonElement(MessageData.serializer(), it)) } }
            val cursor = body.field("cursor")?.asObjectOrNull()?.field("next").str()
            val headers = if (cursor.isNullOrBlank()) Headers.headersOf() else Headers.headersOf("X-Next-Cursor", cursor)
            Response.success(array, headers)
        }
    }

    suspend fun sendMessage(id: String, body: SendMessageBody, directory: String?): MessageData = when (protocol()) {
        Protocol.V1 -> v1.sendMessage(id, body.copy(model = body.model?.copy(variant = null)), directory)
        Protocol.V2 -> {
            v2ApplySelection(id, body)
            val resp = v2.prompt(id, promptPayload(body, body.parts))
            mapMessage(resp.dataObject(), id)
        }
    }

    suspend fun sendMessageAsync(id: String, body: SendMessageBody, directory: String?): Response<Unit> = when (protocol()) {
        // The 1.x prompt schema has no variant; keep its payload byte-compatible.
        Protocol.V1 -> v1.sendMessageAsync(id, body.copy(model = body.model?.copy(variant = null)), directory)
        Protocol.V2 -> {
            v2ApplySelection(id, body)
            runCatching { v2.prompt(id, promptPayload(body, body.parts)) }
            Response.success(Unit)
        }
    }

    suspend fun command(id: String, body: CommandBody, directory: String?): MessageData = when (protocol()) {
        Protocol.V1 -> v1.command(id, body.copy(providerID = null, variant = null), directory)
        Protocol.V2 -> {
            body.agent?.let { v2.setAgent(id, buildJsonObject { put("agent", it) }) }
            if (body.model != null && body.providerID != null) {
                v2.setModel(id, buildJsonObject {
                    put("model", modelRefJson(ModelRef(body.providerID, body.model, body.variant)))
                })
            }
            val payload = buildJsonObject {
                put("name", body.command)
                put("text", body.arguments)
            }
            mapMessage(runCatching { v2.command(id, payload) }.getOrNull()?.asJsonObject()?.dataObject() ?: JsonObject(emptyMap()), id)
        }
    }

    suspend fun sessionDiff(id: String, messageId: String?, directory: String?): List<FileDiff> = when (protocol()) {
        Protocol.V1 -> v1.sessionDiff(id, messageId, directory)
        Protocol.V2 -> {
            val from = v2.sessionDiff(id, from = messageId, to = null).asJsonObject()
            (from.field("data").arr() ?: JsonArray(emptyList())).mapNotNull { e ->
                val o = e.asObjectOrNull() ?: return@mapNotNull null
                FileDiff(
                    file = o.field("file").str().orEmpty(),
                    patch = o.field("patch").str().orEmpty(),
                    additions = (o.field("additions").long() ?: 0).toInt(),
                    deletions = (o.field("deletions").long() ?: 0).toInt(),
                )
            }
        }
    }

    suspend fun abort(id: String, directory: String?): Boolean = when (protocol()) {
        Protocol.V1 -> v1.abort(id, directory)
        Protocol.V2 -> runCatching { v2.interrupt(id); true }.getOrDefault(false)
    }

    suspend fun todos(id: String, directory: String?): List<Todo> = when (protocol()) {
        Protocol.V1 -> v1.todos(id, directory)
        // V2 has no todo endpoint; the panel simply stays empty.
        Protocol.V2 -> emptyList()
    }

    // ---------------- files / search ----------------

    suspend fun listFiles(path: String, directory: String?): List<FileNode> = when (protocol()) {
        Protocol.V1 -> v1.listFiles(path, directory)
        Protocol.V2 -> {
            val data = v2.fsList(directory, path).asJsonObject().field("data").arr() ?: JsonArray(emptyList())
            data.mapNotNull { e ->
                val o = e.asObjectOrNull() ?: return@mapNotNull null
                val raw = o.field("path").str().orEmpty().trimEnd('/')
                val type = o.field("type").str().orEmpty()
                FileNode(name = raw.substringAfterLast('/'), path = raw, absolute = "", type = type, ignored = false)
            }
        }
    }

    suspend fun fileContent(path: String, directory: String?): FileContent = when (protocol()) {
        Protocol.V1 -> v1.fileContent(path, directory)
        Protocol.V2 -> {
            val encoded = URLEncoder.encode(path, "UTF-8").replace("+", "%20").replace("%2F", "/")
            val url = buildString {
                append(baseUrl())
                append("api/fs/read/")
                append(encoded)
                if (directory != null) append("?location[directory]=").append(URLEncoder.encode(directory, "UTF-8"))
            }
            val bytes = v2.fsRead(url).use { it.bytes() }
            val binary = bytes.any { it == 0.toByte() }
            FileContent(type = if (binary) "binary" else "text", content = if (binary) "" else String(bytes, Charsets.UTF_8))
        }
    }

    suspend fun findText(pattern: String, directory: String?): List<FindMatch> = when (protocol()) {
        Protocol.V1 -> v1.findText(pattern, directory)
        // V2 dropped ripgrep content search.
        Protocol.V2 -> emptyList()
    }

    suspend fun findFiles(query: String, limit: Int?, directory: String?): List<String> = when (protocol()) {
        Protocol.V1 -> v1.findFiles(query, limit, directory)
        Protocol.V2 -> {
            val data = v2.fsFind(directory, query, "file", limit).asJsonObject().field("data").arr() ?: JsonArray(emptyList())
            data.mapNotNull { it.asObjectOrNull()?.field("path").str() }
        }
    }

    // ---------------- agents / providers / commands ----------------

    suspend fun agents(): List<Agent> = when (protocol()) {
        Protocol.V1 -> v1.agents()
        Protocol.V2 -> v2.agents().arrayData().mapNotNull { e ->
            val o = e.asObjectOrNull() ?: return@mapNotNull null
            if (o.field("hidden").bool() == true) return@mapNotNull null
            Agent(
                name = o.field("id").str().orEmpty(),
                description = o.field("description").str(),
                mode = o.field("mode").str() ?: "primary",
                color = o.field("color").str(),
            )
        }
    }

    suspend fun providers(): ProviderList = when (protocol()) {
        Protocol.V1 -> v1.providers()
        Protocol.V2 -> {
            val providers = v2.providers().arrayData().mapNotNull { it.asObjectOrNull() }
            val models = runCatching { v2.models().arrayData().mapNotNull { it.asObjectOrNull() } }.getOrDefault(emptyList())
            val byProvider = models.groupBy { it.field("providerID").str().orEmpty() }
            val all = providers.map { p ->
                val pid = p.field("id").str().orEmpty()
                Provider(
                    id = pid,
                    name = p.field("name").str() ?: pid,
                    source = "",
                    models = byProvider[pid].orEmpty().associate { m ->
                        val mid = m.field("modelID").str() ?: m.field("id").str().orEmpty()
                        val limit = m.field("limit")?.asObjectOrNull()
                        mid to ModelInfo(
                            id = mid,
                            name = m.field("name").str() ?: mid,
                            limit = limit?.let {
                                ModelLimit(
                                    context = it.field("context").long() ?: 0,
                                    output = it.field("output").long() ?: 0,
                                )
                            },
                            variants = m.field("variants").arr().orEmpty().mapNotNull { v ->
                                v.asObjectOrNull()?.field("id")?.str()?.let { ModelVariant(it) }
                            },
                        )
                    },
                )
            }
            ProviderList(all = all, default = emptyMap(), connected = all.map { it.id })
        }
    }

    suspend fun commands(): List<Command> = when (protocol()) {
        Protocol.V1 -> v1.commands()
        Protocol.V2 -> v2.commands().arrayData().mapNotNull { e ->
            val o = e.asObjectOrNull() ?: return@mapNotNull null
            Command(name = o.field("name").str().orEmpty(), description = o.field("description").str())
        }
    }

    // ---------------- pty ----------------

    suspend fun ptyList(directory: String?): List<PtyInfo> = when (protocol()) {
        Protocol.V1 -> v1.ptyList(directory)
        Protocol.V2 -> v2.ptyList(directory).arrayData().mapNotNull { it.asObjectOrNull()?.let { o -> mapPty(o) } }
    }

    suspend fun ptyCreate(body: PtyCreateBody, directory: String?): PtyInfo = when (protocol()) {
        Protocol.V1 -> v1.ptyCreate(body, directory)
        Protocol.V2 -> {
            val payload = buildJsonObject {
                body.command?.let { put("command", it) }
                body.args?.let { putJsonArray("args") { it.forEach { a -> add(JsonPrimitive(a)) } } }
                body.cwd?.let { put("cwd", it) }
                body.title?.let { put("title", it) }
                body.env?.let { env -> put("env", buildJsonObject { env.forEach { (k, v) -> put(k, v) } }) }
            }
            mapPty(v2.ptyCreate(directory, payload).dataObject())
        }
    }

    suspend fun ptyGet(id: String, directory: String?): PtyInfo = when (protocol()) {
        Protocol.V1 -> v1.ptyGet(id, directory)
        Protocol.V2 -> ptyList(directory).firstOrNull { it.id == id } ?: PtyInfo(id = id)
    }

    suspend fun ptyUpdate(id: String, body: PtyUpdateBody, directory: String?): PtyInfo = when (protocol()) {
        Protocol.V1 -> v1.ptyUpdate(id, body, directory)
        // V2 pty sizing is handled over the connect socket; nothing to persist here.
        Protocol.V2 -> ptyGet(id, directory)
    }

    suspend fun ptyDelete(id: String, directory: String?): Boolean = when (protocol()) {
        Protocol.V1 -> v1.ptyDelete(id, directory)
        Protocol.V2 -> v2.ptyDelete(id, directory).isSuccessful
    }

    suspend fun ptyShells(directory: String?): List<PtyShell> = when (protocol()) {
        Protocol.V1 -> v1.ptyShells(directory)
        Protocol.V2 -> v2.shells().asJsonArray().mapNotNull { e ->
            val o = e.asObjectOrNull() ?: return@mapNotNull null
            PtyShell(
                path = o.field("path").str().orEmpty(),
                name = o.field("name").str().orEmpty(),
                acceptable = o.field("acceptable").bool() ?: true,
            )
        }
    }

    /** The websocket URL a [dev.opencode.mobile.terminal.OkHttpPtyTransport] should connect to. */
    fun ptyConnectBase(): String = when (resolved ?: Protocol.V1) {
        Protocol.V1 -> baseUrl() + "pty/"
        Protocol.V2 -> baseUrl() + "api/pty/"
    }

    /** V2 pty connects with a short-lived ticket instead of basic auth; null for V1. */
    suspend fun ptyTicket(id: String, directory: String?): String? = when (protocol()) {
        Protocol.V1 -> null
        Protocol.V2 -> runCatching { v2.ptyConnectToken(id, directory).dataOf()?.field("ticket").str() }.getOrNull()
    }

    // ---------------- permissions / questions (forms) ----------------

    suspend fun replyPermission(id: String, permissionID: String, body: PermissionReplyBody, directory: String?): Response<Unit> = when (protocol()) {
        Protocol.V1 -> v1.replyPermission(id, permissionID, body, directory)
        Protocol.V2 -> v2.permissionReply(id, permissionID, buildJsonObject { put("decision", body.response) })
    }

    suspend fun replyPermissionV2(requestID: String, body: PermissionReplyV2Body, directory: String?): Response<Unit> = when (protocol()) {
        Protocol.V1 -> v1.replyPermissionV2(requestID, body, directory)
        // V2 permissions are session-scoped; the legacy call carries the session id.
        Protocol.V2 -> Response.success(Unit)
    }

    suspend fun pendingPermissions(directory: String?): List<PermissionRequest> = when (protocol()) {
        Protocol.V1 -> v1.pendingPermissions(directory)
        Protocol.V2 -> {
            val data = v2.permissionRequests(directory).asJsonObject().field("data").arr() ?: JsonArray(emptyList())
            data.mapNotNull { e -> e.asObjectOrNull()?.let { o -> mapPermissionRequest(o) } }
        }
    }

    suspend fun pendingQuestions(directory: String?): List<QuestionRequest> = when (protocol()) {
        Protocol.V1 -> v1.pendingQuestions(directory)
        Protocol.V2 -> v2.allForms(directory).arrayData().mapNotNull { it.asObjectOrNull()?.let { o -> mapFormToQuestionRequest(o) } }
    }

    suspend fun replyQuestion(request: QuestionRequest, answers: List<List<String>>, directory: String?): Response<Unit> = when (protocol()) {
        Protocol.V1 -> v1.replyQuestion(request.id, QuestionReplyBody(answers), directory)
        Protocol.V2 -> {
            val answer = buildJsonObject {
                request.questions.forEachIndexed { index, q ->
                    val chosen = answers.getOrNull(index).orEmpty()
                    if (chosen.isNotEmpty()) {
                        val mapped = chosen.map { label -> q.options.firstOrNull { it.label == label }?.value?.ifBlank { label } ?: label }
                        if (q.multiple) putJsonArray(q.key) { mapped.forEach { add(JsonPrimitive(it)) } }
                        else put(q.key, mapped.first())
                    }
                }
            }
            v2.formReply(request.sessionID, request.id, buildJsonObject { put("answer", answer) })
        }
    }

    suspend fun rejectQuestion(request: QuestionRequest, directory: String?): Response<Unit> = when (protocol()) {
        Protocol.V1 -> v1.rejectQuestion(request.id, directory)
        // V2 forms have no reject route; dismissing locally is enough.
        Protocol.V2 -> Response.success(Unit)
    }

    // ---------------- V2 helpers ----------------

    private suspend fun v2ApplySelection(id: String, body: SendMessageBody) {
        body.model?.let { v2.setModel(id, buildJsonObject { put("model", modelRefJson(it)) }) }
        body.agent?.let { v2.setAgent(id, buildJsonObject { put("agent", it) }) }
    }

    private fun modelRefJson(model: ModelRef): JsonObject = buildJsonObject {
        put("id", model.modelID)
        put("providerID", model.providerID)
        model.variant?.takeIf { it.isNotBlank() }?.let { put("variant", it) }
    }

    private fun promptPayload(body: SendMessageBody, parts: List<dev.opencode.mobile.data.model.PartInput>): JsonObject = buildJsonObject {
        val text = parts.filter { it.type == "text" }.joinToString("\n") { it.text }
        put("text", text)
        val files = parts.filter { it.type == "file" && it.url != null }
        if (files.isNotEmpty()) {
            putJsonArray("files") {
                files.forEach { f ->
                    add(buildJsonObject {
                        put("uri", f.url.orEmpty())
                        f.filename?.let { put("name", it) }
                    })
                }
            }
        }
    }

    private fun mapSession(o: JsonObject): Session {
        val location = o.field("location")?.asObjectOrNull()
        val tokens = o.field("tokens")?.asObjectOrNull()
        val time = o.field("time")?.asObjectOrNull()
        val revert = o.field("revert")?.asObjectOrNull()
        return Session(
            id = o.field("id").str().orEmpty(),
            projectID = o.field("projectID").str().orEmpty(),
            directory = location?.field("directory").str().orEmpty(),
            parentID = o.field("parentID").str(),
            title = o.field("title").str().orEmpty(),
            cost = o.field("cost").dbl(),
            tokens = tokens?.let { mapTokens(it) },
            time = time?.let {
                SessionTime(
                    created = it.field("created").long() ?: 0,
                    updated = it.field("updated").long() ?: 0,
                )
            },
            revert = revert?.let { SessionRevert(messageID = it.field("messageID").str().orEmpty()) },
        )
    }

    private fun mapTokens(o: JsonObject): Tokens {
        val cache = o.field("cache")?.asObjectOrNull()
        return Tokens(
            input = o.field("input").long() ?: 0,
            output = o.field("output").long() ?: 0,
            reasoning = o.field("reasoning").long() ?: 0,
            cache = Cache(
                read = cache?.field("read").long() ?: 0,
                write = cache?.field("write").long() ?: 0,
            ),
        )
    }

    private fun mapProject(o: JsonObject): Project = Project(
        id = o.field("id").str().orEmpty(),
        worktree = o.field("canonical").str().orEmpty(),
        vcs = o.field("vcs").str(),
        time = o.field("time")?.asObjectOrNull()?.let {
            ProjectTime(created = it.field("created").long() ?: 0, initialized = it.field("updated").long())
        },
    )

    private fun mapPty(o: JsonObject): PtyInfo = PtyInfo(
        id = o.field("id").str().orEmpty(),
        title = o.field("title").str().orEmpty(),
        command = o.field("command").str().orEmpty(),
        args = (o.field("args").arr() ?: JsonArray(emptyList())).mapNotNull { it.str() },
        cwd = o.field("cwd").str().orEmpty(),
        status = o.field("status").str().orEmpty(),
        pid = (o.field("pid").long() ?: 0).toInt(),
        exitCode = o.field("exitCode").long()?.toInt(),
    )

    private fun mapMessage(o: JsonObject, sessionId: String): MessageData {
        val type = o.field("type").str().orEmpty()
        val id = o.field("id").str().orEmpty()
        val time = o.field("time")?.asObjectOrNull()
        val model = o.field("model")?.asObjectOrNull()
        val info = Message(
            id = id,
            sessionID = o.field("sessionID").str() ?: sessionId,
            role = type,
            time = time?.let { MessageTime(created = it.field("created").long() ?: 0, completed = it.field("completed").long()) },
            agent = o.field("agent").str(),
            providerID = model?.field("providerID").str(),
            modelID = model?.field("id").str(),
            cost = o.field("cost").dbl(),
            tokens = o.field("tokens")?.asObjectOrNull()?.let { mapTokens(it) },
            error = o.field("error")?.asObjectOrNull(),
            finish = o.field("finish").str(),
        )
        val parts = when (type) {
            "user" -> listOf(Part(id = "${id}_text", sessionID = info.sessionID, messageID = id, type = "text", text = o.field("text").str().orEmpty()))
            "assistant" -> (o.field("content").arr() ?: JsonArray(emptyList())).mapIndexedNotNull { index, e ->
                e.asObjectOrNull()?.let { c -> mapContent(c, id, info.sessionID, index) }
            }
            else -> listOfNotNull(o.field("text").str()?.takeIf { it.isNotBlank() }?.let {
                Part(id = "${id}_text", sessionID = info.sessionID, messageID = id, type = "text", text = it)
            })
        }
        return MessageData(info = info, parts = parts)
    }

    private fun mapContent(c: JsonObject, messageId: String, sessionId: String, index: Int): Part? {
        val type = c.field("type").str().orEmpty()
        return when (type) {
            "text" -> Part(id = "$messageId:$index", sessionID = sessionId, messageID = messageId, type = "text", text = c.field("text").str().orEmpty())
            "reasoning" -> Part(id = "$messageId:$index", sessionID = sessionId, messageID = messageId, type = "reasoning", text = c.field("text").str().orEmpty())
            "tool" -> {
                val state = c.field("state")?.asObjectOrNull()
                val status = state?.field("status").str().orEmpty()
                val input = state?.field("input")?.asObjectOrNull()
                val metadata = state?.field("metadata")?.asObjectOrNull()
                val contentText = (state?.field("content")?.arr() ?: JsonArray(emptyList())).mapNotNull { item ->
                    val io = item.asObjectOrNull() ?: return@mapNotNull null
                    when (io.field("type").str()) {
                        "text" -> io.field("text").str()
                        "file" -> io.field("name").str() ?: io.field("uri").str()
                        else -> null
                    }
                }.joinToString("\n")
                val errorText = state?.field("error")?.let { err ->
                    (err.asObjectOrNull()?.field("message").str()) ?: err.toString()
                }.orEmpty()
                Part(
                    id = c.field("id").str() ?: "$messageId:t:$index",
                    sessionID = sessionId,
                    messageID = messageId,
                    type = "tool",
                    callID = c.field("id").str().orEmpty(),
                    tool = c.field("name").str().orEmpty(),
                    state = dev.opencode.mobile.data.model.ToolState(
                        status = status,
                        input = input,
                        output = contentText,
                        error = errorText,
                        metadata = metadata,
                        time = c.field("time")?.asObjectOrNull()?.let {
                            PartTime(start = it.field("created").long() ?: 0, end = it.field("completed").long())
                        },
                    ),
                )
            }
            else -> null
        }
    }

    private fun JsonElement.asJsonObject(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonElement.asJsonArray(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

    /** `{ data: ... }` envelope. */
    private fun JsonElement.dataOf(): JsonObject? = (this as? JsonObject)?.field("data")?.asObjectOrNull()
    private fun JsonElement.dataObject(): JsonObject = dataOf() ?: asJsonObject()
    private fun JsonElement.arrayData(): JsonArray = (this as? JsonObject)?.field("data")?.arr() ?: JsonArray(emptyList())
    private fun JsonElement.arrayOrData(): JsonArray = (this as? JsonArray) ?: arrayData()
}

/** Maps a V2 `Form.Info` payload to the app's question model so the shared prompt UI can render it. */
internal fun mapFormToQuestionRequest(o: JsonObject): QuestionRequest {
    val fields = o.field("fields").arr() ?: JsonArray(emptyList())
    val questions = fields.mapNotNull { e ->
        val f = e.asObjectOrNull() ?: return@mapNotNull null
        val type = f.field("type").str().orEmpty()
        QuestionInfo(
            question = f.field("description").str()?.ifBlank { null } ?: f.field("title").str().orEmpty(),
            header = f.field("title").str() ?: f.field("key").str().orEmpty(),
            options = (f.field("options").arr() ?: JsonArray(emptyList())).mapNotNull { oe ->
                val oo = oe.asObjectOrNull() ?: return@mapNotNull null
                QuestionOption(
                    label = oo.field("label").str().orEmpty(),
                    description = oo.field("description").str().orEmpty(),
                    value = oo.field("value").str().orEmpty(),
                )
            },
            multiple = type == "multiselect",
            custom = f.field("custom").bool() ?: false,
            key = f.field("key").str().orEmpty(),
        )
    }
    return QuestionRequest(
        id = o.field("id").str().orEmpty(),
        sessionID = o.field("sessionID").str().orEmpty(),
        questions = questions,
    )
}

/** Maps a V2 `Permission.Request` payload to the app's permission model. */
internal fun mapPermissionRequest(o: JsonObject): PermissionRequest = PermissionRequest(
    id = o.field("id").str().orEmpty(),
    sessionID = o.field("sessionID").str().orEmpty(),
    permission = o.field("action").str().orEmpty(),
    title = o.field("message").str() ?: o.field("action").str().orEmpty(),
    patterns = (o.field("resources").arr() ?: JsonArray(emptyList())).mapNotNull { it.str() },
    metadata = o.field("metadata").asObjectOrNull(),
)
