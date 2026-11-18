// Metro (React Native / Expo bundler) per project root, owned by the helper — not by a phone
// connection — so a 4G drop doesn't kill the bundler. Logs live in a ring buffer and are broadcast to
// every connected phone; a reconnecting phone catches up with `metro.status`.
import { spawn, type ChildProcess } from "node:child_process"
import { existsSync, readFileSync } from "node:fs"
import { join } from "node:path"

export type MetroKind = "expo" | "react-native"
export type LogLevel = "info" | "warn" | "error"

export interface MetroCommand {
  kind: MetroKind
  /** argv; argv[0] is resolved from node_modules/.bin when present, else run through npx. */
  argv: string[]
  /** Shown in the app ("npx expo start --port 8081"). */
  label: string
}

export interface LogLine { seq: number; time: number; level: LogLevel; text: string; frame?: boolean }

export interface MetroStatus {
  root: string
  state: "stopped" | "starting" | "running" | "external" | "exited"
  kind: MetroKind | null
  command: string | null
  port: number
  pid: number | null
  startedAt: number | null
  exitCode: number | null
  errors: number
}

const MAX_LINES = 2000
// ESC sequences (colour, cursor moves, clear line) that Metro's TTY-less output still carries.
const ANSI = /\u001b\[[0-9;?]*[ -\/]*[@-~]|\u001b\][^\u0007]*\u0007|\u001b[()][A-Z0-9]/g
const PROGRESS = /^(?:BUNDLE|Android Bundl|iOS Bundl|Web Bundl|Bundl)\S*\s.*\d+(?:\.\d+)?%/

/** What `npm start`-equivalent starts Metro for this project, or why there is none. */
export function detectMetro(root: string, port = 8081): MetroCommand {
  const file = join(root, "package.json")
  if (!existsSync(file)) throw new Error("No package.json in the project folder")
  let pkg: any
  try { pkg = JSON.parse(readFileSync(file, "utf8")) } catch { throw new Error("package.json isn't valid JSON") }
  const deps = { ...(pkg.devDependencies ?? {}), ...(pkg.dependencies ?? {}) }
  const start = String(pkg.scripts?.start ?? "")
  // Expo wins when present (an Expo app also depends on react-native); a bare `react-native start`
  // script means the project opted out of the Expo CLI.
  const expo = "expo" in deps && !/\breact-native\s+start\b/.test(start)
  if (expo) return { kind: "expo", argv: ["expo", "start", "--port", String(port)], label: `npx expo start --port ${port}` }
  if ("react-native" in deps) return { kind: "react-native", argv: ["react-native", "start", "--port", String(port)], label: `npx react-native start --port ${port}` }
  throw new Error("Not a React Native or Expo project (no react-native or expo dependency)")
}

/** Info/warn/error for a Metro log line (stderr lines are at least warnings). */
export function classify(text: string, stderr: boolean): LogLevel {
  if (/\b(error|failed|exception)\b|SyntaxError|TypeError|ReferenceError|Unable to resolve|TransformError|EADDRINUSE|ENOSPC|^\s*ERROR\b/i.test(text) && !/\b0 errors?\b/i.test(text)) return "error"
  if (/\bwarn(ing)?\b|deprecated/i.test(text)) return "warn"
  return stderr ? "warn" : "info"
}

/** Whether something already answers Metro's status endpoint on this port. */
export async function metroAlive(port: number): Promise<boolean> {
  try {
    const res = await fetch(`http://127.0.0.1:${port}/status`, { signal: AbortSignal.timeout(1500) })
    return (await res.text()).includes("packager-status:running")
  } catch {
    return false
  }
}

type Listener = (event: "log" | "status", root: string, payload: unknown) => void

class MetroProcess {
  status: MetroStatus
  lines: LogLine[] = []
  private seq = 0
  private proc: ChildProcess | null = null
  private partial = { out: "", err: "" }
  private poll: NodeJS.Timeout | null = null
  /** Set by stop(): the exit that follows is "stopped", not a crash. */
  private stopping = false

  constructor(root: string, port: number, private emit: Listener) {
    this.status = { root, state: "stopped", kind: null, command: null, port, pid: null, startedAt: null, exitCode: null, errors: 0 }
  }

  start(command: MetroCommand) {
    const root = this.status.root
    const local = join(root, "node_modules", ".bin", command.argv[0])
    const [bin, args] = existsSync(local) ? [local, command.argv.slice(1)] : ["npx", ["--no-install", ...command.argv]]
    this.lines = []
    this.stopping = false
    Object.assign(this.status, { state: "starting", kind: command.kind, command: command.label, pid: null, startedAt: Date.now(), exitCode: null, errors: 0 })
    // Status first: phones clear the previous run's log when they see "starting".
    this.changed()
    this.push(`$ ${command.label}`, "info")
    const proc = spawn(bin, args, {
      cwd: root,
      // Own process group, so stop() also takes down the watchers / workers Metro forks.
      detached: true,
      stdio: ["pipe", "pipe", "pipe"],
      // No CI=1: Expo turns off reloads in CI mode. FORCE_COLOR=0 keeps chalk quiet (with NO_COLOR also set, Node warns on every worker).
      env: { ...withoutNoColor(process.env), FORCE_COLOR: "0", RCT_METRO_PORT: String(this.status.port) },
    })
    this.proc = proc
    this.status.pid = proc.pid ?? null
    proc.stdout!.on("data", (c: Buffer) => this.read(c, false))
    proc.stderr!.on("data", (c: Buffer) => this.read(c, true))
    proc.on("error", (e) => this.push(`Couldn't start Metro: ${e.message}`, "error"))
    proc.on("exit", (code, signal) => {
      this.flush()
      if (this.proc !== proc) return
      this.proc = null
      this.stopPolling()
      const requested = this.stopping
      Object.assign(this.status, { state: requested ? "stopped" : "exited", pid: null, exitCode: code ?? (signal ? 128 : null) })
      if (requested) this.push("Metro stopped", "info")
      else this.push(signal ? `Metro was killed (${signal})` : `Metro exited with code ${code}`, code ? "error" : "info")
      this.changed()
    })
    this.poll = setInterval(async () => {
      if (this.status.state !== "starting") return this.stopPolling()
      if (await metroAlive(this.status.port)) {
        this.status.state = "running"
        this.stopPolling()
        this.changed()
      }
    }, 700)
    this.changed()
  }

  stop(): Promise<void> {
    const proc = this.proc
    if (!proc?.pid) {
      if (this.status.state === "external") Object.assign(this.status, { state: "stopped" })
      this.changed()
      return Promise.resolve()
    }
    this.stopping = true
    return new Promise((done) => {
      const timer = setTimeout(() => { try { process.kill(-proc.pid!, "SIGKILL") } catch { /* gone */ } }, 4000)
      proc.once("exit", () => { clearTimeout(timer); done() })
      try { process.kill(-proc.pid!, "SIGTERM") } catch { try { proc.kill("SIGTERM") } catch { /* gone */ } }
    })
  }

  /** Reload the connected apps: Metro's /reload broadcast, or `r` on stdin as a fallback. */
  async reload(): Promise<"http" | "stdin"> {
    try {
      const res = await fetch(`http://127.0.0.1:${this.status.port}/reload`, { method: "POST", signal: AbortSignal.timeout(2000) })
      if (res.ok) { this.push("Reload sent to connected apps", "info"); return "http" }
    } catch { /* fall through */ }
    if (this.proc?.stdin?.writable) { this.proc.stdin.write("r"); this.push("Reload sent (r)", "info"); return "stdin" }
    throw new Error("Metro isn't reachable")
  }

  markExternal() {
    Object.assign(this.status, { state: "external", kind: null, command: null, pid: null, startedAt: null, exitCode: null })
    this.push(`Metro is already running on port ${this.status.port} (started outside the app — logs aren't available here)`, "warn")
    this.changed()
  }

  get running() { return this.proc != null }

  private read(chunk: Buffer, stderr: boolean) {
    const key = stderr ? "err" : "out"
    const text = this.partial[key] + chunk.toString("utf8")
    const parts = text.split(/\r\n|\n|\r/)
    this.partial[key] = parts.pop() ?? ""
    for (const p of parts) this.line(p, stderr)
  }

  private flush() {
    for (const key of ["out", "err"] as const) {
      if (this.partial[key]) this.line(this.partial[key], key === "err")
      this.partial[key] = ""
    }
  }

  private line(raw: string, stderr: boolean) {
    const text = raw.replace(ANSI, "").replace(/\s+$/, "")
    if (!text.trim()) return
    // Code-frame lines ("> 2 | import …", "    |   ^") belong to the error above them.
    const last = this.lines[this.lines.length - 1]
    const frame = /^\s*(?:>?\s*\d+\s*)?\|/.test(text) && last != null && last.level !== "info"
    this.push(text, frame ? "frame" : classify(text, stderr))
  }

  private push(text: string, level: LogLevel | "frame") {
    if (level === "frame") {
      const prev = this.lines[this.lines.length - 1]?.level ?? "info"
      // Same colour as its error, but it doesn't count as another problem.
      return this.append(text, prev, false)
    }
    this.append(text, level, true)
  }

  private append(text: string, level: LogLevel, counts: boolean) {
    const last = this.lines[this.lines.length - 1]
    // Bundling progress repaints one line: replace it instead of flooding the log.
    const replace = last != null && PROGRESS.test(text) && PROGRESS.test(last.text)
    const line: LogLine = { seq: replace ? last.seq : ++this.seq, time: Date.now(), level, text, ...(counts ? {} : { frame: true }) }
    if (replace) this.lines[this.lines.length - 1] = line
    else {
      this.lines.push(line)
      if (this.lines.length > MAX_LINES) this.lines.splice(0, this.lines.length - MAX_LINES)
    }
    if (level === "error" && counts) this.status.errors++
    this.emit("log", this.status.root, { line, replace })
  }

  private stopPolling() {
    if (this.poll) clearInterval(this.poll)
    this.poll = null
  }

  private changed() {
    this.emit("status", this.status.root, { ...this.status })
  }
}

/** All Metro processes this helper runs, keyed by project root. */
export class MetroManager {
  private byRoot = new Map<string, MetroProcess>()
  private listeners = new Set<Listener>()

  subscribe(l: Listener) { this.listeners.add(l); return () => { this.listeners.delete(l) } }

  private get(root: string, port = 8081) {
    let m = this.byRoot.get(root)
    if (!m) {
      m = new MetroProcess(root, port, (e, r, p) => { for (const l of this.listeners) l(e, r, p) })
      this.byRoot.set(root, m)
    }
    return m
  }

  /** Status + the last [tail] log lines (a phone catching up after connecting). */
  status(root: string, tail = 500) {
    const m = this.byRoot.get(root)
    let detected: string | null = null
    let detectError: string | null = null
    try { detected = detectMetro(root, m?.status.port ?? 8081).label } catch (e: any) { detectError = e.message }
    const status = m?.status ?? { root, state: "stopped", kind: null, command: null, port: 8081, pid: null, startedAt: null, exitCode: null, errors: 0 }
    return { ...status, detected, detectError, lines: (m?.lines ?? []).slice(-tail) }
  }

  async start(root: string, port = 8081) {
    const command = detectMetro(root, port)
    const m = this.get(root, port)
    if (m.running) return { ...m.status }
    // Another root's Metro on the same port would answer every app: say so instead of failing later.
    for (const other of this.byRoot.values()) {
      if (other !== m && other.running && other.status.port === port) throw new Error(`Port ${port} is used by Metro for ${other.status.root}`)
    }
    m.status.port = port
    if (await metroAlive(port)) { m.markExternal(); return { ...m.status } }
    m.start(command)
    return { ...m.status }
  }

  async stop(root: string) {
    const m = this.byRoot.get(root)
    if (m) await m.stop()
    return m ? { ...m.status } : null
  }

  async reload(root: string) {
    const m = this.byRoot.get(root)
    if (!m || (m.status.state !== "running" && m.status.state !== "external" && m.status.state !== "starting")) throw new Error("Metro isn't running")
    return { via: await m.reload() }
  }

  async stopAll() {
    await Promise.all([...this.byRoot.values()].map((m) => m.stop()))
  }
}

function withoutNoColor(env: NodeJS.ProcessEnv): NodeJS.ProcessEnv {
  const { NO_COLOR: _drop, ...rest } = env
  return rest
}
