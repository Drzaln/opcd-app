package dev.opencode.mobile.data.net

import dev.opencode.mobile.data.model.GoUsage
import dev.opencode.mobile.data.model.GoUsageEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class GoUsageClient {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(apiKey: String): GoUsage = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://opencode.ai/zen/go/v1/usage")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    when (response.code) {
                        401, 403 -> "Invalid API key"
                        else -> "Usage request failed (${response.code})"
                    },
                )
            }
            json.decodeFromString<GoUsageEnvelope>(body).usage
        }
    }
}
