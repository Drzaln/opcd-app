package dev.opencode.mobile.data.net

data class GitResult(val output: String, val exitCode: Int) {
    val ok: Boolean get() = exitCode == 0
}

/** One line of `git status --porcelain` (v1): two status letters and a path (the new path for renames). */
data class GitChange(val index: Char, val worktree: Char, val path: String) {
    val untracked: Boolean get() = index == '?' && worktree == '?'
    val label: String get() = when {
        untracked -> "U"
        index == 'A' || worktree == 'A' -> "A"
        index == 'D' || worktree == 'D' -> "D"
        index == 'R' || worktree == 'R' -> "R"
        else -> "M"
    }
}

/**
 * Runs a one-shot shell command on the server through a PTY and captures its output.
 *
 * The server exposes no endpoint to mutate VCS state (`/vcs` is read-only), so branch
 * switching/creation, status, diff and commit run git itself via [PtyOneShot].
 */
class GitRunner(
    server: ServerConfig,
    api: OpenCodeApi,
) {
    private val pty = PtyOneShot(server, api)
    private val exitMarker = PtyOneShot.EXIT

    suspend fun run(command: String, dir: String?, timeoutMs: Long = 15_000): GitResult {
        val script = "$command\n${PtyOneShot.EXIT_PRINTF} \$?\nsleep 60"
        val result = pty.run(script, dir, title = "git", timeoutMs = timeoutMs)
        if (result.failure != null && result.output.isEmpty()) return GitResult(result.failure, -1)
        return parse(result.output)
    }

    /** `git status --porcelain` for the project, or null when it isn't a repository / git failed. */
    suspend fun status(dir: String?): List<GitChange>? {
        val result = run("$GIT -c core.quotePath=false status --porcelain", dir)
        if (!result.ok) return null
        return parseStatus(result.output)
    }

    /** Unified diff of one file against HEAD (untracked files diff against /dev/null). */
    suspend fun diff(change: GitChange, dir: String?): GitResult {
        val path = quote("./" + change.path)
        val command = if (change.untracked) {
            // --no-index exits 1 when the files differ, which is the normal case here.
            "$GIT diff --no-index -- /dev/null $path; true"
        } else {
            "$GIT diff HEAD -- $path 2>/dev/null || $GIT diff --cached -- $path"
        }
        return run(command, dir, timeoutMs = 30_000)
    }

    suspend fun commitAll(message: String, dir: String?): GitResult {
        require(isValidCommitMessage(message)) { "Commit message is empty or has invalid characters" }
        return run("$GIT add -A && $GIT commit -q -m ${quote(message.trim())}", dir, timeoutMs = 30_000)
    }

    fun parse(raw: String): GitResult {
        val clean = PtyOneShot.stripAnsi(raw).replace("\r\n", "\n").replace('\r', '\n')
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
        // Trim blank lines only: `status --porcelain` lines start with a significant space (" M file").
        return GitResult(output.joinToString("\n").trimStart('\n').trimEnd(), exit)
    }

    companion object {
        /** No pager (a TTY would start one) and no colour codes. */
        const val GIT = "git --no-pager -c color.ui=false"

        val BRANCH_NAME = Regex("^[A-Za-z0-9/_.-]+$")

        fun isValidBranch(name: String): Boolean =
            BRANCH_NAME.matches(name) && !name.startsWith("-") && !name.contains("..")

        fun isValidCommitMessage(message: String): Boolean =
            message.isNotBlank() && message.none { it == '\u0000' }

        fun quote(value: String): String = EditorFiles.shellQuote(value)

        fun parseStatus(output: String): List<GitChange> = output.lines().mapNotNull { line ->
            if (line.length < 4 || line[2] != ' ') return@mapNotNull null
            var path = line.substring(3)
            val arrow = path.indexOf(" -> ")
            if (arrow >= 0) path = path.substring(arrow + 4)
            if (path.length >= 2 && path.startsWith('"') && path.endsWith('"')) {
                path = path.substring(1, path.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            }
            GitChange(line[0], line[1], path.trimEnd('/'))
        }
    }
}
