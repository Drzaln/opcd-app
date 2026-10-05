package dev.opencode.mobile.data.net

import dev.opencode.mobile.data.model.PtyCreateBody
import dev.opencode.mobile.terminal.OkHttpPtyTransport
import dev.opencode.mobile.terminal.PtyEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class GitResult(val output: String, val exitCode: Int) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * Runs a one-shot shell command on the server through a PTY and captures its output.
 *
 * The server exposes no endpoint to mutate VCS state (`/vcs` is read-only), so branch
 * switching/creation runs git itself. The PTY is the only primitive that streams the
 * command's stdout/stderr; we append an exit-code marker and read the socket to completion.
 *
 * The PTY is removed the moment the command exits, so a fast git command would be reaped
 * before the WebSocket handshake finishes (404, "Expected HTTP 101"). A trailing `sleep`
 * keeps it alive; we stop reading as soon as the exit marker shows up.
 */
class GitRunner(
    private val server: ServerConfig,
    private val api: OpenCodeApi,
) {
    private val exitMarker = "__OC_EXIT__"
    private val exitPattern = Regex(exitMarker + "[0-9]+")

    suspend fun run(command: String, dir: String?, timeoutMs: Long = 15_000): GitResult {
        val script = "$command\nprintf '$exitMarker%s\\n' \$?\nsleep 60"
        val pty = runCatching {
            api.ptyCreate(
                PtyCreateBody(command = "sh", args = listOf("-c", script), cwd = dir, title = "git"),
                dir,
            )
        }.getOrElse { return GitResult("Could not start command: ${it.message ?: "unknown error"}", -1) }

        val transport = OkHttpPtyTransport(server, pty.id, dir)
        val buffer = StringBuilder()
        var failure: String? = null
        val done = CompletableDeferred<Unit>()

        try {
            withTimeoutOrNull(timeoutMs) {
                coroutineScope {
                    val job = launch {
                        transport.events.collect { event ->
                            when (event) {
                                is PtyEvent.Data -> {
                                    buffer.append(event.text)
                                    if (exitPattern.containsMatchIn(buffer)) done.complete(Unit)
                                }
                                is PtyEvent.Failed -> { failure = event.message; done.complete(Unit) }
                                is PtyEvent.Closed -> done.complete(Unit)
                                is PtyEvent.Meta -> {}
                            }
                        }
                    }
                    done.await()
                    job.cancel()
                }
            }
        } finally {
            transport.close()
            runCatching { api.ptyDelete(pty.id, dir) }
        }

        if (failure != null && buffer.isEmpty()) return GitResult(failure!!, -1)
        return parse(buffer.toString())
    }

    fun parse(raw: String): GitResult {
        val clean = stripAnsi(raw).replace("\r\n", "\n").replace('\r', '\n')
        val lines = clean.split('\n')
        var exit = -1
        val output = ArrayList<String>(lines.size)
        for (line in lines) {
            val marker = line.indexOf(exitMarker)
            if (marker >= 0) {
                exit = line.substring(marker + exitMarker.length).trim().toIntOrNull() ?: -1
                break
            }
            output.add(line)
        }
        return GitResult(output.joinToString("\n").trim(), exit)
    }

    private fun stripAnsi(text: String): String =
        text.replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "").replace(Regex("\u001B[@-Z\\\\-_]"), "")

    companion object {
        val BRANCH_NAME = Regex("^[A-Za-z0-9/_.-]+$")

        fun isValidBranch(name: String): Boolean =
            BRANCH_NAME.matches(name) && !name.startsWith("-") && !name.contains("..")
    }
}
