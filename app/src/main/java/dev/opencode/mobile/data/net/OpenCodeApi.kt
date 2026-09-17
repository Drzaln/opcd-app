package dev.opencode.mobile.data.net

import dev.opencode.mobile.data.model.Project
import dev.opencode.mobile.data.model.Agent
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
import dev.opencode.mobile.data.model.MessageData
import dev.opencode.mobile.data.model.PermissionReplyBody
import dev.opencode.mobile.data.model.PermissionReplyV2Body
import dev.opencode.mobile.data.model.PermissionRequest
import dev.opencode.mobile.data.model.ProviderList
import dev.opencode.mobile.data.model.PtyCreateBody
import dev.opencode.mobile.data.model.PtyInfo
import dev.opencode.mobile.data.model.PtyShell
import dev.opencode.mobile.data.model.PtyUpdateBody
import dev.opencode.mobile.data.model.QuestionReplyBody
import dev.opencode.mobile.data.model.QuestionRequest
import dev.opencode.mobile.data.model.RevertBody
import dev.opencode.mobile.data.model.SendMessageBody
import dev.opencode.mobile.data.model.Session
import dev.opencode.mobile.data.model.SessionStatus
import dev.opencode.mobile.data.model.SessionUpdateBody
import dev.opencode.mobile.data.model.SummarizeBody
import dev.opencode.mobile.data.model.Todo
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.PUT
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface OpenCodeApi {

    @GET("global/health")
    suspend fun health(): Health

    @GET("project")
    suspend fun projects(): List<Project>

    @GET("project/current")
    suspend fun currentProject(@Query("directory") directory: String? = null): Project

    @GET("path")
    suspend fun currentPath(@Query("directory") directory: String? = null): JsonObject

    @GET("session")
    suspend fun sessions(@Query("directory") directory: String? = null): List<Session>

    @POST("session")
    suspend fun createSession(
        @Body body: CreateSessionBody,
        @Query("directory") directory: String? = null,
    ): Session

    @GET("session/{id}")
    suspend fun session(
        @Path("id") id: String,
        @Query("directory") directory: String? = null,
    ): Session

    @PATCH("session/{id}")
    suspend fun updateSession(
        @Path("id") id: String,
        @Body body: SessionUpdateBody,
        @Query("directory") directory: String? = null,
    ): Session

    @DELETE("session/{id}")
    suspend fun deleteSession(
        @Path("id") id: String,
        @Query("directory") directory: String? = null,
    ): Boolean

    @GET("session/status")
    suspend fun sessionStatus(@Query("directory") directory: String? = null): Map<String, SessionStatus>

    @POST("session/{id}/fork")
    suspend fun forkSession(
        @Path("id") id: String,
        @Body body: ForkBody = ForkBody(),
        @Query("directory") directory: String? = null,
    ): Session

    @POST("session/{id}/revert")
    suspend fun revertSession(
        @Path("id") id: String,
        @Body body: RevertBody,
        @Query("directory") directory: String? = null,
    ): Session

    @POST("session/{id}/unrevert")
    suspend fun unrevertSession(
        @Path("id") id: String,
        @Query("directory") directory: String? = null,
    ): Session

    @POST("session/{id}/summarize")
    suspend fun summarizeSession(
        @Path("id") id: String,
        @Body body: SummarizeBody,
        @Query("directory") directory: String? = null,
    ): Boolean

    @POST("session/{id}/share")
    suspend fun shareSession(
        @Path("id") id: String,
        @Query("directory") directory: String? = null,
    ): Session

    @DELETE("session/{id}/share")
    suspend fun unshareSession(
        @Path("id") id: String,
        @Query("directory") directory: String? = null,
    ): Session

    @DELETE("session/{id}/message/{messageID}")
    suspend fun deleteMessage(
        @Path("id") id: String,
        @Path("messageID") messageID: String,
        @Query("directory") directory: String? = null,
    ): Boolean

    @GET("session/{id}/message")
    suspend fun messages(
        @Path("id") id: String,
        @Query("limit") limit: Int? = null,
        @Query("directory") directory: String? = null,
    ): kotlinx.serialization.json.JsonArray

    @GET("session/{id}/message")
    suspend fun messagesPage(
        @Path("id") id: String,
        @Query("limit") limit: Int,
        @Query("before") before: String? = null,
        @Query("directory") directory: String? = null,
    ): Response<kotlinx.serialization.json.JsonArray>

    @POST("session/{id}/message")
    suspend fun sendMessage(
        @Path("id") id: String,
        @Body body: SendMessageBody,
        @Query("directory") directory: String? = null,
    ): MessageData

    @POST("session/{id}/prompt_async")
    suspend fun sendMessageAsync(
        @Path("id") id: String,
        @Body body: SendMessageBody,
        @Query("directory") directory: String? = null,
    ): Response<Unit>

    @POST("session/{id}/command")
    suspend fun command(
        @Path("id") id: String,
        @Body body: CommandBody,
        @Query("directory") directory: String? = null,
    ): MessageData

    @GET("session/{id}/diff")
    suspend fun sessionDiff(
        @Path("id") id: String,
        @Query("messageID") messageId: String? = null,
        @Query("directory") directory: String? = null,
    ): List<FileDiff>

    @POST("session/{id}/abort")
    suspend fun abort(
        @Path("id") id: String,
        @Query("directory") directory: String? = null,
    ): Boolean

    @GET("session/{id}/todo")
    suspend fun todos(
        @Path("id") id: String,
        @Query("directory") directory: String? = null,
    ): List<Todo>

    @GET("file")
    suspend fun listFiles(
        @Query("path") path: String,
        @Query("directory") directory: String? = null,
    ): List<FileNode>

    @GET("file/content")
    suspend fun fileContent(
        @Query("path") path: String,
        @Query("directory") directory: String? = null,
    ): FileContent

    @GET("file/status")
    suspend fun fileStatus(@Query("directory") directory: String? = null): List<FileStatus>

    @GET("find")
    suspend fun findText(
        @Query("pattern") pattern: String,
        @Query("directory") directory: String? = null,
    ): List<FindMatch>

    @GET("find/file")
    suspend fun findFiles(
        @Query("query") query: String,
        @Query("limit") limit: Int? = null,
        @Query("directory") directory: String? = null,
    ): List<String>

    @GET("agent")
    suspend fun agents(): List<Agent>

    @GET("provider")
    suspend fun providers(): ProviderList

    @GET("command")
    suspend fun commands(): List<Command>

    // --- pty (terminal) ---
    @GET("pty")
    suspend fun ptyList(@Query("directory") directory: String? = null): List<PtyInfo>

    @POST("pty")
    suspend fun ptyCreate(
        @Body body: PtyCreateBody,
        @Query("directory") directory: String? = null,
    ): PtyInfo

    @GET("pty/{id}")
    suspend fun ptyGet(@Path("id") id: String, @Query("directory") directory: String? = null): PtyInfo

    @PUT("pty/{id}")
    suspend fun ptyUpdate(
        @Path("id") id: String,
        @Body body: PtyUpdateBody,
        @Query("directory") directory: String? = null,
    ): PtyInfo

    @DELETE("pty/{id}")
    suspend fun ptyDelete(@Path("id") id: String, @Query("directory") directory: String? = null): Boolean

    @GET("pty/shells")
    suspend fun ptyShells(@Query("directory") directory: String? = null): List<PtyShell>

    // --- permissions ---
    // Legacy (opencode <= ~1.18.14): session-scoped reply with `response`.
    @POST("session/{id}/permissions/{permissionID}")
    suspend fun replyPermission(
        @Path("id") id: String,
        @Path("permissionID") permissionID: String,
        @Body body: PermissionReplyBody,
        @Query("directory") directory: String? = null,
    ): Response<Unit>

    // Newer (opencode 1.18.31+): global reply with `reply`.
    @POST("permission/{requestID}/reply")
    suspend fun replyPermissionV2(
        @Path("requestID") requestID: String,
        @Body body: PermissionReplyV2Body,
        @Query("directory") directory: String? = null,
    ): Response<Unit>

    @GET("permission")
    suspend fun pendingPermissions(@Query("directory") directory: String? = null): List<PermissionRequest>    // --- questions (the agent's ask tool) ---
    @GET("question")
    suspend fun pendingQuestions(@Query("directory") directory: String? = null): List<QuestionRequest>

    @POST("question/{requestID}/reply")
    suspend fun replyQuestion(
        @Path("requestID") requestID: String,
        @Body body: QuestionReplyBody,
        @Query("directory") directory: String? = null,
    ): Response<Unit>

    @POST("question/{requestID}/reject")
    suspend fun rejectQuestion(
        @Path("requestID") requestID: String,
        @Query("directory") directory: String? = null,
    ): Response<Unit>
}