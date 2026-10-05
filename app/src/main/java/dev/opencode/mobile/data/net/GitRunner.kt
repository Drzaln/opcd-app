package dev.opencode.mobile.data.net

import dev.opencode.mobile.data.model.PtyCreateBody
import dev.opencode.mobile.terminal.OkHttpPtyTransport
import dev.opencode.mobile.terminal.PtyEvent
import kotlinx.coroutines.flow.takeWhile
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
 */
class GitRunner(
    private val server: ServerConfig,
    private val api: OpenCodeApi,
) {
    private val exitMarker = "__OC_EXIT__"

    suspend fun run(command: String, dir: String?, timeoutMs: Long = 15_000): GitResult {
        val script = "$command\nprintf '$exitMarker%s\\n' \$?"
        val pty = runCatching {
            api.ptyCreate(
                PtyCreateBody(command = "sh", args = listOf("-c", script), cwd = dir, title = "git"),
                dir,
            )
        }.getOrElse { return GitResult("Could not start command: ${it.message ?: "unknown error"}", -1) }

        val transport = OkHttpPtyTransport(server, pty.id, dir)
        val buffer = StringBuilder()
        var failure: String? = null

        try {
            withTimeoutOrNull(timeoutMs) {
                transport.events
                    .takeWhile { event ->
                        when (event) {
                            is PtyEvent.Closed -> false
                            is PtyEvent.Failed -> { failure = event.message; false }
                            else -> true
                        }
                    }
                    .collect { event -> if (event is PtyEvent.Data) buffer.append(event.text) }
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
