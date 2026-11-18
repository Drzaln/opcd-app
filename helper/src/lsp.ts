// One typescript-language-server process per editor session, relayed as raw JSON-RPC strings.
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process"
import { createRequire } from "node:module"
import { dirname, join } from "node:path"

const require = createRequire(import.meta.url)

/** Path of the bundled typescript-language-server CLI and the bundled TypeScript (fallback tsserver). */
function serverPaths() {
  const cli = join(dirname(require.resolve("typescript-language-server/package.json")), "lib", "cli.mjs")
  const tsserver = join(dirname(require.resolve("typescript/package.json")), "lib", "tsserver.js")
  return { cli, tsserver }
}

export class LspSession {
  private proc: ChildProcessWithoutNullStreams
  private buffer = Buffer.alloc(0)
  closed = false

  constructor(
    readonly id: string,
    readonly root: string,
    private onMessage: (message: string) => void,
    private onExit: (code: number | null) => void,
  ) {
    const { cli, tsserver } = serverPaths()
    // The project's own TypeScript wins when present (typescript-language-server looks in the
    // workspace first); the bundled one is the fallback for projects without it.
    this.proc = spawn(process.execPath, [cli, "--stdio", "--log-level", "2"], {
      cwd: root,
      env: { ...process.env, TSS_LOG: "" },
    })
    this.proc.stdout.on("data", (chunk: Buffer) => this.read(chunk))
    this.proc.stderr.on("data", (chunk: Buffer) => process.stderr.write(`[lsp ${id}] ${chunk}`))
    this.proc.on("exit", (code) => {
      this.closed = true
      this.onExit(code)
    })
    this.fallbackTsserver = tsserver
  }

  /** Used to fill `initializationOptions.tsserver.fallbackPath` when the client doesn't set one. */
  fallbackTsserver: string

  send(message: string) {
    if (this.closed) return
    let text = message
    // Point the server at our bundled TypeScript if the project has none of its own.
    try {
      const parsed = JSON.parse(message)
      if (parsed?.method === "initialize") {
        parsed.params = parsed.params ?? {}
        const opts = (parsed.params.initializationOptions ??= {})
        opts.tsserver = { fallbackPath: this.fallbackTsserver, ...(opts.tsserver ?? {}) }
        opts.preferences = { includeCompletionsForModuleExports: true, includeCompletionsWithInsertText: true, ...(opts.preferences ?? {}) }
        text = JSON.stringify(parsed)
      }
    } catch {
      return
    }
    const body = Buffer.from(text, "utf8")
    this.proc.stdin.write(`Content-Length: ${body.length}\r\n\r\n`)
    this.proc.stdin.write(body)
  }

  private read(chunk: Buffer) {
    this.buffer = Buffer.concat([this.buffer, chunk])
    for (;;) {
      const headerEnd = this.buffer.indexOf("\r\n\r\n")
      if (headerEnd < 0) return
      const header = this.buffer.subarray(0, headerEnd).toString("ascii")
      const match = /Content-Length:\s*(\d+)/i.exec(header)
      if (!match) { this.buffer = this.buffer.subarray(headerEnd + 4); continue }
      const length = Number(match[1])
      const start = headerEnd + 4
      if (this.buffer.length < start + length) return
      const body = this.buffer.subarray(start, start + length).toString("utf8")
      this.buffer = this.buffer.subarray(start + length)
      this.onMessage(body)
    }
  }

  dispose() {
    if (this.closed) return
    this.closed = true
    this.proc.kill()
  }
}
