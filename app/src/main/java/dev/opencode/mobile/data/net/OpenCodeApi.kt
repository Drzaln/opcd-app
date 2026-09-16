package dev.opencode.mobile.data.net

import dev.opencode.mobile.data.model.Project
import dev.opencode.mobile.data.model.CreateSessionBody
import dev.opencode.mobile.data.model.FileContent
import dev.opencode.mobile.data.model.FileDiff
import dev.opencode.mobile.data.model.FileNode
import dev.opencode.mobile.data.model.FileStatus
import dev.opencode.mobile.data.model.Health
import dev.opencode.mobile.data.model.MessageData
import dev.opencode.mobile.data.model.SendMessageBody
import dev.opencode.mobile.data.model.Session
import dev.opencode.mobile.data.model.SessionStatus
import dev.opencode.mobile.data.model.Todo
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface OpenCodeApi {

    @GET("global/health")
    suspend fun health(): Health

    @GET("project/current")
    suspend fun currentProject(): Project

    @GET("path")
    suspend fun currentPath(): JsonObject

    @GET("session")
    suspend fun sessions(): List<Session>

    @POST("session")
    suspend fun createSession(@Body body: CreateSessionBody): Session

    @GET("session/{id}")
    suspend fun session(@Path("id") id: String): Session

    @DELETE("session/{id}")
    suspend fun deleteSession(@Path("id") id: String): Boolean

    @GET("session/status")
    suspend fun sessionStatus(): Map<String, SessionStatus>

    @GET("session/{id}/message")
    suspend fun messages(
        @Path("id") id: String,
        @Query("limit") limit: Int? = null,
    ): List<MessageData>

    @POST("session/{id}/message")
    suspend fun sendMessage(
        @Path("id") id: String,
        @Body body: SendMessageBody,
    ): MessageData

    @POST("session/{id}/prompt_async")
    suspend fun sendMessageAsync(
        @Path("id") id: String,
        @Body body: SendMessageBody,
    ): Response<Unit>

    @GET("session/{id}/diff")
    suspend fun sessionDiff(
        @Path("id") id: String,
        @Query("messageID") messageId: String? = null,
    ): List<FileDiff>

    @POST("session/{id}/abort")
    suspend fun abort(@Path("id") id: String): Boolean

    @GET("session/{id}/todo")
    suspend fun todos(@Path("id") id: String): List<Todo>

    @GET("file")
    suspend fun listFiles(@Query("path") path: String): List<FileNode>

    @GET("file/content")
    suspend fun fileContent(@Query("path") path: String): FileContent

    @GET("file/status")
    suspend fun fileStatus(): List<FileStatus>

    @GET("agent")
    suspend fun agents(): List<JsonObject>

    @GET("command")
    suspend fun commands(): List<JsonObject>
}