package dev.opencode.mobile.data.net

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * opencode V2 HTTP surface (`/api/...`). Responses are returned as raw [JsonElement] and mapped
 * to the app's internal V1-shaped models by [OpenCodeApi]; this keeps the mapping in one place and
 * tolerates the many envelope/union shapes V2 uses.
 */
interface OpenCodeV2Api {

    @GET("api/info")
    suspend fun info(): JsonElement

    @GET("api/location")
    suspend fun location(@Query("location[directory]") directory: String? = null): JsonElement

    @GET("api/project")
    suspend fun projects(): JsonElement

    @GET("api/vcs")
    suspend fun vcs(@Query("location[directory]") directory: String? = null): JsonElement

    @GET("api/vcs/status")
    suspend fun vcsStatus(@Query("location[directory]") directory: String? = null): JsonElement

    @GET("api/session")
    suspend fun sessions(
        @Query("directory") directory: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("cursor") cursor: String? = null,
    ): JsonElement

    @POST("api/session")
    suspend fun createSession(@Body body: JsonObject): JsonElement

    @GET("api/session/{id}")
    suspend fun session(
        @Path("id") id: String,
        @Query("directory") directory: String? = null,
    ): JsonElement

    @PATCH("api/session/{id}")
    suspend fun updateSession(
        @Path("id") id: String,
        @Body body: JsonObject,
        @Query("directory") directory: String? = null,
    ): JsonElement

    @DELETE("api/session/{id}")
    suspend fun deleteSession(
        @Path("id") id: String,
        @Query("directory") directory: String? = null,
    ): Response<Unit>

    @GET("api/session/active")
    suspend fun sessionActive(): JsonElement

    @GET("api/session/{id}/message")
    suspend fun messages(
        @Path("id") id: String,
        @Query("limit") limit: Int? = null,
        @Query("cursor") cursor: String? = null,
    ): JsonElement

    @POST("api/session/{id}/prompt")
    suspend fun prompt(@Path("id") id: String, @Body body: JsonObject): JsonElement

    @POST("api/session/{id}/command")
    suspend fun command(@Path("id") id: String, @Body body: JsonObject): JsonElement

    @GET("api/session/{id}/diff")
    suspend fun sessionDiff(
        @Path("id") id: String,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): JsonElement

    @POST("api/session/{id}/interrupt")
    suspend fun interrupt(@Path("id") id: String): JsonElement

    @POST("api/session/{id}/compact")
    suspend fun compact(@Path("id") id: String, @Body body: JsonObject): JsonElement

    @POST("api/session/{id}/revert/stage")
    suspend fun revertStage(@Path("id") id: String, @Body body: JsonObject): JsonElement

    @POST("api/session/{id}/revert/commit")
    suspend fun revertCommit(@Path("id") id: String): JsonElement

    @DELETE("api/session/{id}/revert")
    suspend fun revertClear(@Path("id") id: String): Response<Unit>

    @POST("api/session/{id}/model")
    suspend fun setModel(@Path("id") id: String, @Body body: JsonObject): Response<Unit>

    @POST("api/session/{id}/agent")
    suspend fun setAgent(@Path("id") id: String, @Body body: JsonObject): Response<Unit>

    @POST("api/session/{id}/fork")
    suspend fun fork(@Path("id") id: String, @Body body: JsonObject): JsonElement

    @GET("api/agent")
    suspend fun agents(@Query("location[directory]") directory: String? = null): JsonElement

    @GET("api/command")
    suspend fun commands(@Query("location[directory]") directory: String? = null): JsonElement

    @GET("api/provider")
    suspend fun providers(@Query("location[directory]") directory: String? = null): JsonElement

    @GET("api/model")
    suspend fun models(@Query("location[directory]") directory: String? = null): JsonElement

    @GET("api/fs/list")
    suspend fun fsList(
        @Query("location[directory]") directory: String? = null,
        @Query("path") path: String? = null,
    ): JsonElement

    @GET("api/fs/find")
    suspend fun fsFind(
        @Query("location[directory]") directory: String? = null,
        @Query("query") query: String,
        @Query("type") type: String,
        @Query("limit") limit: Int? = null,
    ): JsonElement

    @Streaming
    @GET
    suspend fun fsRead(@Url url: String): ResponseBody

    @GET("api/permission/request")
    suspend fun permissionRequests(@Query("location[directory]") directory: String? = null): JsonElement

    @POST("api/session/{id}/permission/{requestID}/reply")
    suspend fun permissionReply(
        @Path("id") id: String,
        @Path("requestID") requestID: String,
        @Body body: JsonObject,
    ): Response<Unit>

    @GET("api/form")
    suspend fun allForms(@Query("location[directory]") directory: String? = null): JsonElement

    @GET("api/session/{id}/form")
    suspend fun forms(@Path("id") id: String): JsonElement

    @POST("api/session/{id}/form/{formID}/reply")
    suspend fun formReply(
        @Path("id") id: String,
        @Path("formID") formID: String,
        @Body body: JsonObject,
    ): Response<Unit>

    @GET("api/pty")
    suspend fun ptyList(@Query("location[directory]") directory: String? = null): JsonElement

    @POST("api/pty")
    suspend fun ptyCreate(
        @Query("location[directory]") directory: String? = null,
        @Body body: JsonObject,
    ): JsonElement

    @DELETE("api/pty/{id}")
    suspend fun ptyDelete(
        @Path("id") id: String,
        @Query("location[directory]") directory: String? = null,
    ): Response<Unit>

    @POST("api/pty/{id}/connect-token")
    suspend fun ptyConnectToken(
        @Path("id") id: String,
        @Query("location[directory]") directory: String? = null,
    ): JsonElement

    @GET("api/config/shell")
    suspend fun shells(): JsonElement
}
