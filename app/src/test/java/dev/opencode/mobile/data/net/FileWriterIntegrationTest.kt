package dev.opencode.mobile.data.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File

/**
 * Opt-in: runs against a live server (`make serve`, :4199, password `secret`).
 * `OC_IT=1 ./gradlew :app:testDebugUnitTest --tests '*FileWriterIntegrationTest*'`
 */
class FileWriterIntegrationTest {
    private lateinit var dir: File
    private lateinit var writer: FileWriter

    @Before fun setUp() {
        assumeTrue("set OC_IT=1 to run against make serve", System.getenv("OC_IT") == "1")
        // One fixed folder: the server remembers every directory it is asked about as a project.
        dir = File(System.getProperty("java.io.tmpdir"), "oc-editor-it").canonicalFile.apply { deleteRecursively(); mkdirs() }
        val server = ServerConfig(id = "it", name = "it", baseUrl = "http://127.0.0.1:4199", password = "secret")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", Credentials.basic(server.username, server.password)).build())
        }.build()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        val retrofit = Retrofit.Builder().baseUrl("http://127.0.0.1:4199/").client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
        val api = OpenCodeApi(server, retrofit.create(OpenCodeV1Api::class.java), retrofit.create(OpenCodeV2Api::class.java), client)
        writer = FileWriter(server, api)
    }

    private fun roundTrip(viaPty: Boolean) = runBlocking {
        writer.forcePty = viaPty
        val sub = File(dir, "src dir").apply { mkdirs() }
        val file = File(sub, "it's App.tsx")
        file.writeText("export const a = 1\n")
        file.setExecutable(true)

        val loaded = (writer.read("src dir/it's App.tsx", dir.path) as ReadResult.Ok).file
        assertEquals("export const a = 1\n", loaded.text)

        val big = buildString { var i = 0; while (length < 1_500_000) append("line ${i++} — ünï ✓ ${"x".repeat(i % 200)}\r\n") }
        val saved = writer.save(loaded.path, big, loaded.hash, dir.path)
        assertTrue("save: $saved", saved is SaveResult.Saved)
        assertEquals(big, file.readText())
        assertEquals(EditorFiles.sha256(big), EditorFiles.sha256(file.readBytes()))
        if (viaPty) assertTrue("mode kept", file.canExecute())
        assertTrue("no temp files left", sub.listFiles()!!.none { it.name.endsWith(".tmp") })

        // Someone edits the file on the Mac: the next save must not overwrite it.
        file.writeText("changed on the Mac\n")
        val conflict = writer.save(loaded.path, "phone edit\n", (saved as SaveResult.Saved).hash, dir.path)
        assertTrue("conflict: $conflict", conflict is SaveResult.Conflict)
        assertEquals("changed on the Mac\n", (conflict as SaveResult.Conflict).remote?.text)
        assertEquals("changed on the Mac\n", file.readText())

        val forced = writer.save(loaded.path, "phone edit\n", saved.hash, dir.path, force = true)
        assertTrue("forced: $forced", forced is SaveResult.Saved)
        assertEquals("phone edit\n", file.readText())

        val escape = writer.save("../escape.txt", "x", null, dir.path)
        assertTrue(escape is SaveResult.Failed)
        assertTrue(!File(dir.parentFile, "escape.txt").exists())
    }

    /** The 1.x read path: exact bytes through the PTY (base64 between markers). */
    @Test fun ptyReadIsExact() = runBlocking {
        val bytes = ("  leading space\n" + "ü".repeat(70_000) + "\n\n\n").toByteArray()
        File(dir, "r.txt").writeBytes(bytes)
        assertEquals(EditorFiles.sha256(bytes), EditorFiles.sha256(writer.ptyRead("r.txt", dir.path)))
        File(dir, "huge.bin").writeBytes(ByteArray(EditorFiles.MAX_BYTES + 1))
        val failed = runCatching { writer.ptyRead("huge.bin", dir.path) }.exceptionOrNull()
        assertTrue("too big: $failed", failed?.message?.contains("2 MB") == true)
    }

    @Test fun nativeRoute() = roundTrip(viaPty = false)

    @Test fun ptyWriter() = roundTrip(viaPty = true)
}
