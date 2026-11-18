package dev.opencode.mobile.data.net

import dev.opencode.mobile.data.model.PtyCreateBody
import dev.opencode.mobile.terminal.OkHttpPtyTransport
import dev.opencode.mobile.terminal.PtyEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Raw outcome of [PtyOneShot.run]: the captured output, or why nothing could be captured. */
data class PtyRun(val output: String, val failure: String?, val timedOut: Boolean)

/**
 * Runs one `sh -c` script on the server through a throwaway PTY and returns everything it printed.
 *
 * The server has no exec route, so the PTY is the only way to run a command and see its output
 * (git, file writes). Rules learned the hard way (see PROGRESS.md "Git branch"):
 * - the script must end with `printf '__OC_EXIT__%s\n' $r` and then `sleep 60`, because the server
 *   reaps the PTY the instant its command exits — a fast command is gone before the WebSocket
 *   handshake finishes (connect 404). We stop reading as soon as the exit marker shows up;
 * - when the caller feeds input, the script prints [READY] first (after `stty -echo`), and input is
 *   only sent once that marker arrives, so nothing typed is echoed back or lost to a not-yet-running
 *   shell.
 */
class PtyOneShot(
    private val server: ServerConfig,
    private val api: OpenCodeApi,
) {
    suspend fun run(
        script: String,
        dir: String?,
        title: String,
        timeoutMs: Long,
        input: (suspend (send: (String) -> Unit) -> Unit)? = null,
    ): PtyRun {
        val pty = try {
            api.ptyCreate(PtyCreateBody(command = "sh", args = listOf("-c", script), cwd = dir, title = title), dir)
        } catch (e: Exception) {
            return PtyRun("", "Could not start command: ${e.message ?: "unknown error"}", false)
        }

        val transport = OkHttpPtyTransport(server, pty.id, dir, protocol = api.protocol(), ticket = api.ptyTicket(pty.id, dir))
        val buffer = StringBuilder()
        var failure: String? = null
        var readySeen = input == null
        var scanFrom = 0
        val done = CompletableDeferred<Unit>()

        val finished = try {
            withTimeoutOrNull(timeoutMs) {
                coroutineScope {
                    val job = launch {
                        transport.events.collect { event ->
                            when (event) {
                                is PtyEvent.Data -> {
                                    buffer.append(event.text)
                                    // Scan only the new tail (+ a marker's length of overlap): outputs can be MBs.
                                    val from = (scanFrom - 32).coerceAtLeast(0)
                                    if (!readySeen && buffer.indexOf(READY, from) >= 0) {
                                        readySeen = true
                                        launch { input?.invoke { transport.send(it) } }
                                    }
                                    val exit = buffer.indexOf(EXIT, from)
                                    if (exit >= 0 && buffer.indexOf("\n", exit) >= 0) done.complete(Unit)
                                    scanFrom = buffer.length
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
                true
            } ?: false
        } finally {
            transport.close()
            runCatching { api.ptyDelete(pty.id, dir) }
        }
        return PtyRun(buffer.toString(), failure, timedOut = !finished)
    }

    companion object {
        const val EXIT = "__OC_EXIT__"
        const val READY = "__OC_READY__"

        private val ANSI_CSI = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
        private val ANSI_ESC = Regex("\u001B[@-Z\\\\-_]")

        fun stripAnsi(text: String): String = text.replace(ANSI_CSI, "").replace(ANSI_ESC, "")

        /** `printf` format for the exit line: the script prints `__OC_EXIT__<code>`. */
        const val EXIT_PRINTF = "printf '__OC_EXIT__%s\\n'"

        fun exitCode(output: String): Int? =
            Regex("$EXIT(\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull()
    }
}
