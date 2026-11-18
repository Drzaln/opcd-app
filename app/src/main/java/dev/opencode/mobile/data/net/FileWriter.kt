package dev.opencode.mobile.data.net

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64

/** Pure helpers for the editor's read/save path. Kept free of Android APIs so they are unit-tested. */
object EditorFiles {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val BASE64_LINE = 76

    sealed interface PathCheck {
        /** [relative] is normalized and relative to the project root (no leading `/`, no `.`/`..`). */
        data class Ok(val relative: String) : PathCheck
        data class Rejected(val reason: String) : PathCheck
    }

    /**
     * Resolves an editor path against the project root and refuses anything that could leave it.
     * Accepts project-relative paths and absolute paths inside [projectDir]; rejects `..` segments
     * (outright, not just when they escape), absolute paths elsewhere, `~`, NUL and line breaks.
     */
    fun resolve(projectDir: String, path: String): PathCheck {
        if (!projectDir.startsWith("/") || projectDir.any { it == '\u0000' || it == '\n' || it == '\r' }) {
            return PathCheck.Rejected("Project folder is not an absolute path")
        }
        if (path.isBlank()) return PathCheck.Rejected("Empty path")
        if (path.any { it == '\u0000' }) return PathCheck.Rejected("Path contains a NUL character")
        if (path.any { it == '\n' || it == '\r' }) return PathCheck.Rejected("Path contains a line break")
        if (path.startsWith("~")) return PathCheck.Rejected("Home-relative paths are not allowed")

        val root = projectDir.trimEnd('/')
        val relative = if (path.startsWith("/")) {
            when {
                path.trimEnd('/') == root -> return PathCheck.Rejected("That is the project folder, not a file")
                root.isEmpty() -> path.trimStart('/')
                path.startsWith("$root/") -> path.removePrefix("$root/")
                else -> return PathCheck.Rejected("Outside the project folder")
            }
        } else {
            path
        }
        val segments = relative.split('/')
        if (segments.any { it == ".." }) return PathCheck.Rejected("Path contains '..'")
        val clean = segments.filter { it.isNotEmpty() && it != "." }
        if (clean.isEmpty()) return PathCheck.Rejected("Empty path")
        return PathCheck.Ok(clean.joinToString("/"))
    }

    /**
     * Base64 in lines of at most [width] chars. Each line is fed to a canonical-mode TTY, whose line
     * buffer is small (MAX_CANON is 1024 on macOS) — longer lines would be truncated.
     */
    fun base64Lines(bytes: ByteArray, width: Int = BASE64_LINE): List<String> {
        require(width in 4..1000 && width % 4 == 0) { "width must be a multiple of 4" }
        val encoded = Base64.getEncoder().encodeToString(bytes)
        return encoded.chunked(width)
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    /** POSIX single-quote escaping: the result is always exactly one shell word. */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** UTF-8 text, or null when the bytes are binary (a NUL byte, like the viewer's sniff, or invalid UTF-8). */
    fun decodeText(bytes: ByteArray): String? {
        if (bytes.any { it == 0.toByte() }) return null
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /** Hidden temp name in the same directory as [relative], so the final `mv` is an atomic rename. */
    fun tempPathFor(relative: String, nonce: String): String {
        val slash = relative.lastIndexOf('/')
        val dir = if (slash >= 0) relative.substring(0, slash + 1) else ""
        val name = relative.substring(slash + 1)
        return "$dir.$name.oc-$nonce.tmp"
    }

    /** Extracts base64 printed between [begin] and [end] markers, dropping TTY noise (CR, spaces). */
    fun extractBase64(output: String, begin: String, end: String): ByteArray? {
        val start = output.indexOf(begin)
        if (start < 0) return null
        val stop = output.indexOf(end, start + begin.length)
        if (stop < 0) return null
        val body = output.substring(start + begin.length, stop).filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        return runCatching { Base64.getDecoder().decode(body) }.getOrNull()
    }

    /**
     * Shell script for the PTY writer: refuses symlinks/non-files, re-checks the on-disk hash
     * against [expectedHash] (conflict guard), then decodes stdin into a temp file beside the target,
     * copies the mode, and `mv`s it over — the real file is never truncated. Prints the new hash.
     */
    fun writeScript(relative: String, temp: String, expectedHash: String?): String = """
        stty -echo 2>/dev/null
        F=${shellQuote("./$relative")}
        T=${shellQuote("./$temp")}
        E=${shellQuote(expectedHash.orEmpty())}
        h() { (shasum -a 256 "${'$'}1" 2>/dev/null || sha256sum "${'$'}1") | cut -d' ' -f1; }
        trap 'rm -f "${'$'}T"' EXIT
        trap 'rm -f "${'$'}T"; exit 1' HUP INT TERM
        main() {
          if [ -L "${'$'}F" ]; then printf '__OC_ERR__symlink\n'; return 4; fi
          if [ -e "${'$'}F" ] && [ ! -f "${'$'}F" ]; then printf '__OC_ERR__notfile\n'; return 4; fi
          if [ -n "${'$'}E" ]; then
            C=none
            if [ -e "${'$'}F" ]; then C=${'$'}(h "${'$'}F"); fi
            if [ "${'$'}C" != "${'$'}E" ]; then printf '__OC_CONFLICT__%s\n' "${'$'}C"; return 9; fi
          fi
          printf '__OC_READY__\n'
          base64 -d > "${'$'}T" || return 5
          if [ -e "${'$'}F" ]; then chmod "${'$'}(stat -c %a "${'$'}F" 2>/dev/null || stat -f %Lp "${'$'}F")" "${'$'}T" 2>/dev/null; fi
          mv -f "${'$'}T" "${'$'}F" || return 6
          printf '__OC_HASH__%s\n' "${'$'}(h "${'$'}F")"
          return 0
        }
        main; r=${'$'}?
        printf '__OC_EXIT__%s\n' ${'$'}r
        sleep 60
    """.trimIndent()

    /** Shell script for the PTY reader (exact bytes, base64 between markers, size-capped). */
    fun readScript(relative: String): String = """
        F=${shellQuote("./$relative")}
        main() {
          if [ ! -e "${'$'}F" ]; then printf '__OC_ERR__missing\n'; return 2; fi
          if [ ! -f "${'$'}F" ]; then printf '__OC_ERR__notfile\n'; return 2; fi
          s=${'$'}(wc -c < "${'$'}F" | tr -d ' ')
          if [ "${'$'}s" -gt $MAX_BYTES ]; then printf '__OC_ERR__toobig\n'; return 3; fi
          printf '__OC_BEGIN__\n'
          base64 < "${'$'}F" || return 5
          printf '\n__OC_END__\n'
          return 0
        }
        main; r=${'$'}?
        printf '__OC_EXIT__%s\n' ${'$'}r
        sleep 60
    """.trimIndent()

    fun errorMessage(code: String): String = when (code) {
        "symlink" -> "The file is a symlink — saving through it isn't supported"
        "notfile" -> "Not a regular file"
        "missing" -> "File not found on the Mac"
        "toobig" -> "File is over 2 MB — too large to edit on the phone"
        else -> code
    }
}

/** A file opened for editing: its text and the SHA-256 of the on-disk bytes it came from. */
data class LoadedFile(val path: String, val text: String, val hash: String)

sealed interface ReadResult {
    data class Ok(val file: LoadedFile) : ReadResult
    data class Failed(val reason: String) : ReadResult
}

sealed interface SaveResult {
    data class Saved(val hash: String, val via: String) : SaveResult
    /** The file on the Mac no longer matches what the editor opened. [remote] is null if it vanished. */
    data class Conflict(val remote: LoadedFile?) : SaveResult
    data class Failed(val reason: String) : SaveResult
}

/**
 * Reads and saves project files for the editor.
 *
 * - **opencode 2.2+** has `POST /api/experimental/fs/write` (see PROGRESS.md "Server integration"):
 *   reads go through `/api/fs/read` (exact bytes), saves through the native route, and both the
 *   conflict check and the post-save verification re-read the file and compare SHA-256.
 * - **Everything else (1.x, 2.0/2.1)** saves through a one-shot PTY ([EditorFiles.writeScript]):
 *   base64 lines over the PTY socket into a temp file, then an atomic `mv`; the script itself checks
 *   the expected hash first and prints the new one. 1.x also *reads* through the PTY, because
 *   `/file/content` returns decoded text, not the bytes on disk.
 */
class FileWriter(
    server: ServerConfig,
    private val api: OpenCodeApi,
) {
    private val pty = PtyOneShot(server, api)

    /** Tests only: save through the PTY even when the server has a native write route. */
    internal var forcePty = false

    /** The project root the editor's relative paths hang off ("Server default" asks the server). */
    suspend fun projectRoot(dir: String?): String? {
        if (!dir.isNullOrBlank()) return dir
        return runCatching {
            (api.currentPath(null)["directory"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        }.getOrNull()?.takeIf { it.startsWith("/") }
    }

    suspend fun read(path: String, dir: String?): ReadResult {
        val root = projectRoot(dir) ?: return ReadResult.Failed("Pick a project folder first")
        val relative = when (val check = EditorFiles.resolve(root, path)) {
            is EditorFiles.PathCheck.Ok -> check.relative
            is EditorFiles.PathCheck.Rejected -> return ReadResult.Failed(check.reason)
        }
        val bytes = try {
            if (api.protocol() == Protocol.V2) api.fileBytes(relative, root) else ptyRead(relative, root)
        } catch (e: retrofit2.HttpException) {
            return ReadResult.Failed(if (e.code() == 404) "File not found on the Mac" else "Read failed (HTTP ${e.code()})")
        } catch (e: ReadError) {
            return ReadResult.Failed(e.message ?: "Read failed")
        } catch (e: Exception) {
            return ReadResult.Failed(e.message ?: "Read failed")
        }
        if (bytes.size > EditorFiles.MAX_BYTES) return ReadResult.Failed(EditorFiles.errorMessage("toobig"))
        val text = EditorFiles.decodeText(bytes) ?: return ReadResult.Failed("Binary file — open it read-only instead")
        return ReadResult.Ok(LoadedFile(relative, text, EditorFiles.sha256(bytes)))
    }

    /**
     * Saves [text] to [path]. Unless [force], refuses (→ [SaveResult.Conflict]) when the file on the Mac
     * no longer hashes to [baseHash]. Success is only reported once the on-disk hash matches ours.
     */
    suspend fun save(path: String, text: String, baseHash: String?, dir: String?, force: Boolean = false): SaveResult {
        val root = projectRoot(dir) ?: return SaveResult.Failed("Pick a project folder first")
        val relative = when (val check = EditorFiles.resolve(root, path)) {
            is EditorFiles.PathCheck.Ok -> check.relative
            is EditorFiles.PathCheck.Rejected -> return SaveResult.Failed(check.reason)
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > EditorFiles.MAX_BYTES) return SaveResult.Failed(EditorFiles.errorMessage("toobig"))
        val hash = EditorFiles.sha256(bytes)
        return try {
            if (!forcePty && api.supportsFileWrite()) nativeSave(relative, root, bytes, hash, baseHash.takeUnless { force })
            else ptySave(relative, root, bytes, hash, baseHash.takeUnless { force })
        } catch (e: Exception) {
            SaveResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun nativeSave(relative: String, root: String, bytes: ByteArray, hash: String, expected: String?): SaveResult {
        if (expected != null) {
            val current = runCatching { api.fileBytes(relative, root) }
            val currentHash = current.getOrNull()?.let { EditorFiles.sha256(it) }
            if (currentHash != expected) return SaveResult.Conflict(remoteOrNull(relative, root))
        }
        api.writeFile(relative, bytes, root)
        val written = EditorFiles.sha256(api.fileBytes(relative, root))
        return if (written == hash) SaveResult.Saved(hash, via = "server")
        else SaveResult.Failed("Verification failed: the Mac has different bytes than were sent")
    }

    private suspend fun ptySave(relative: String, root: String, bytes: ByteArray, hash: String, expected: String?): SaveResult {
        val temp = EditorFiles.tempPathFor(relative, System.currentTimeMillis().toString(36))
        val lines = EditorFiles.base64Lines(bytes)
        // Generous: 2 MB over a slow tailnet link can take a while; the shell side is fast.
        val timeout = 30_000L + bytes.size / 20
        val result = pty.run(
            script = EditorFiles.writeScript(relative, temp, expected),
            dir = root,
            title = "save",
            timeoutMs = timeout,
            input = { send ->
                for (block in lines.chunked(64)) send(block.joinToString("\n", postfix = "\n"))
                send("\u0004") // EOF for base64 -d (Ctrl-D on an empty line).
            },
        )
        val output = PtyOneShot.stripAnsi(result.output)
        Regex("__OC_CONFLICT__(\\S+)").find(output)?.let {
            return SaveResult.Conflict(remoteOrNull(relative, root))
        }
        Regex("__OC_ERR__(\\w+)").find(output)?.let { return SaveResult.Failed(EditorFiles.errorMessage(it.groupValues[1])) }
        if (result.timedOut) return SaveResult.Failed("Timed out talking to the Mac (the file was not replaced)")
        if (result.failure != null && output.isEmpty()) return SaveResult.Failed(result.failure)
        val exit = PtyOneShot.exitCode(output)
        if (exit != 0) return SaveResult.Failed("Save failed on the Mac (exit ${exit ?: "?"})")
        val written = Regex("__OC_HASH__([0-9a-f]{64})").find(output)?.groupValues?.get(1)
            ?: return SaveResult.Failed("Could not verify the save (no hash reported)")
        return if (written == hash) SaveResult.Saved(hash, via = "pty")
        else SaveResult.Failed("Verification failed: hash mismatch after writing")
    }

    private suspend fun remoteOrNull(relative: String, root: String): LoadedFile? =
        (read(relative, root) as? ReadResult.Ok)?.file

    private class ReadError(message: String) : Exception(message)

    internal suspend fun ptyRead(relative: String, root: String): ByteArray {
        val result = pty.run(EditorFiles.readScript(relative), root, title = "read", timeoutMs = 60_000)
        val output = PtyOneShot.stripAnsi(result.output)
        Regex("__OC_ERR__(\\w+)").find(output)?.let { throw ReadError(EditorFiles.errorMessage(it.groupValues[1])) }
        if (result.failure != null && output.isEmpty()) throw ReadError(result.failure)
        if (result.timedOut) throw ReadError("Timed out reading the file")
        return EditorFiles.extractBase64(output, "__OC_BEGIN__", "__OC_END__")
            ?: throw ReadError("Could not read the file (exit ${PtyOneShot.exitCode(output) ?: "?"})")
    }
}
