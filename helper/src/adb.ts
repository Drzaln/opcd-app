// adb on the Mac: devices + wireless pairing, "build & run" for the project, and logcat streams.
//
// Build & run belongs to the helper (like Metro), keyed by project root, so a 4G drop doesn't kill a
// five-minute Gradle build. Logcat is a *view*: it belongs to the phone connection that opened it.
import { execFile, spawn, type ChildProcess } from "node:child_process"
import { existsSync, readFileSync } from "node:fs"
import { homedir } from "node:os"
import { join } from "node:path"
import { classify, type LogLevel } from "./metro.ts"
import { isSimulator } from "./sim.ts"

export interface Device { serial: string; state: string; model: string | null; transport: "usb" | "wifi" | "emulator" }

/** The adb binary: OC_HELPER_ADB (tests), the SDK env vars, the default macOS SDK, then PATH. */
export function findAdb(): string | null {
  const candidates = [
    process.env.OC_HELPER_ADB,
    process.env.ANDROID_HOME && join(process.env.ANDROID_HOME, "platform-tools", "adb"),
    process.env.ANDROID_SDK_ROOT && join(process.env.ANDROID_SDK_ROOT, "platform-tools", "adb"),
    join(homedir(), "Library", "Android", "sdk", "platform-tools", "adb"),
    "/opt/homebrew/bin/adb",
    "/usr/local/bin/adb",
  ].filter(Boolean) as string[]
  for (const c of candidates) if (existsSync(c)) return c
  for (const dir of (process.env.PATH ?? "").split(":")) if (dir && existsSync(join(dir, "adb"))) return join(dir, "adb")
  return null
}

function adb(args: string[], timeoutMs = 15_000): Promise<string> {
  const bin = findAdb()
  if (!bin) return Promise.reject(new Error("adb not found — install Android platform-tools on the Mac"))
  return new Promise((resolve, reject) => {
    execFile(bin, args, { timeout: timeoutMs, maxBuffer: 4 * 1024 * 1024 }, (err, stdout, stderr) => {
      const out = `${stdout}${stderr}`.trim()
      if (err && !out) reject(new Error(err.message))
      else resolve(out)
    })
  })
}

/** `adb devices -l` → devices (emulators, USB, and `host:port` wireless ones). */
export function parseDevices(text: string): Device[] {
  const devices: Device[] = []
  for (const line of text.split("\n").slice(1)) {
    const m = /^(\S+)\s+(device|offline|unauthorized|authorizing|connecting|recovery|sideload|no permissions)\b(.*)$/.exec(line.trim())
    if (!m) continue
    const [, serial, state, rest] = m
    const model = /\bmodel:(\S+)/.exec(rest)?.[1]?.replace(/_/g, " ") ?? null
    const transport = serial.startsWith("emulator-") ? "emulator" : /:\d+$/.test(serial) || serial.includes("._adb-tls-connect.") ? "wifi" : "usb"
    devices.push({ serial, state, model, transport })
  }
  return devices
}

export async function listDevices() {
  const bin = findAdb()
  if (!bin) return { adb: null, devices: [] as Device[] }
  return { adb: bin, devices: parseDevices(await adb(["devices", "-l"])) }
}

const HOST = /^[A-Za-z0-9.\-:\[\]]{1,255}$/
const checkHost = (h: unknown) => { const s = String(h ?? ""); if (!HOST.test(s)) throw new Error("Enter a host name or IP address"); return s }
const checkPortNum = (p: unknown) => { const n = Number(p); if (!Number.isInteger(n) || n < 1 || n > 65535) throw new Error("Enter a port number"); return n }
export const checkSerial = (s: unknown) => { const v = String(s ?? ""); if (!/^[A-Za-z0-9._:\-\[\]]{1,200}$/.test(v)) throw new Error("Pick a device"); return v }

/** Android 11+ wireless debugging: pair once with the 6-digit code, then connect. */
export async function pair(host: unknown, port: unknown, code: unknown) {
  const c = String(code ?? "")
  if (!/^\d{6}$/.test(c)) throw new Error("The pairing code has 6 digits")
  const out = await adb(["pair", `${checkHost(host)}:${checkPortNum(port)}`, c], 30_000)
  if (!/Successfully paired/i.test(out)) throw new Error(out || "Pairing failed")
  return { output: out }
}

export async function connect(host: unknown, port: unknown) {
  const out = await adb(["connect", `${checkHost(host)}:${checkPortNum(port ?? 5555)}`], 20_000)
  if (!/connected to|already connected/i.test(out)) throw new Error(out || "Couldn't connect")
  return { output: out }
}

export async function disconnect(serial: unknown) {
  return { output: await adb(["disconnect", checkSerial(serial)]) }
}

/** Lets the app on the device reach Metro on the Mac as localhost:<port>. */
export async function reverse(serial: unknown, port = 8081) {
  return { output: await adb(["-s", checkSerial(serial), "reverse", `tcp:${port}`, `tcp:${port}`]) }
}

// ── Build & run ─────────────────────────────────────────────────────────────────────────────────

export type RunKind = "expo" | "react-native" | "gradle"
export interface RunCommand { kind: RunKind; bin: string; args: string[]; cwd: string; label: string }

/**
 * How "build & run" works for this project: Expo / React Native CLI, or plain Gradle. A simulator
 * UDID as the serial means iOS (Expo / React Native only: a plain Xcode project isn't detected).
 */
export function detectRun(root: string, serial: string, ios = isSimulator(serial)): RunCommand {
  const pkgFile = join(root, "package.json")
  const pkg = existsSync(pkgFile) ? (() => { try { return JSON.parse(readFileSync(pkgFile, "utf8")) } catch { return {} } })() : null
  const deps = pkg ? { ...(pkg.devDependencies ?? {}), ...(pkg.dependencies ?? {}) } : {}
  const local = (name: string) => existsSync(join(root, "node_modules", ".bin", name))
  const tool = (name: string, args: string[]) =>
    local(name) ? { bin: join(root, "node_modules", ".bin", name), args } : { bin: "npx", args: ["--no-install", name, ...args] }
  if (ios) {
    if ("expo" in deps) {
      const args = ["run:ios", "--device", serial, "--no-bundler"]
      return { kind: "expo", cwd: root, label: `npx expo ${args.join(" ")}`, ...tool("expo", args) }
    }
    if ("react-native" in deps) {
      const args = ["run-ios", "--udid", serial, "--no-packager"]
      return { kind: "react-native", cwd: root, label: `npx react-native ${args.join(" ")}`, ...tool("react-native", args) }
    }
    throw new Error("Build & run on iOS needs an Expo or React Native project")
  }
  if ("expo" in deps) {
    // --no-bundler: Metro is the helper's (Metro tab), not a second one owned by the build.
    const args = ["run:android", "--device", serial, "--no-bundler"]
    return { kind: "expo", cwd: root, label: `npx expo ${args.join(" ")}`, ...tool("expo", args) }
  }
  if ("react-native" in deps) {
    const args = ["run-android", "--deviceId", serial, "--no-packager"]
    return { kind: "react-native", cwd: root, label: `npx react-native ${args.join(" ")}`, ...tool("react-native", args) }
  }
  for (const dir of [root, join(root, "android")]) {
    if (existsSync(join(dir, "gradlew"))) {
      return { kind: "gradle", cwd: dir, bin: join(dir, "gradlew"), args: ["installDebug"], label: `${dir === root ? "" : "android/"}./gradlew installDebug` }
    }
  }
  throw new Error("Nothing to build here: no Expo / React Native dependency and no gradlew")
}

export interface Line { seq: number; level: LogLevel; text: string }
type Emit = (event: "log" | "status", root: string, payload: unknown) => void
const ANSI = /\u001b\[[0-9;?]*[ -\/]*[@-~]|\u001b\][^\u0007]*\u0007/g

export interface RunStatus {
  root: string
  state: "idle" | "running" | "succeeded" | "failed" | "stopped"
  command: string | null
  serial: string | null
  startedAt: number | null
  exitCode: number | null
  errors: number
}

class RunTask {
  status: RunStatus
  lines: Line[] = []
  private seq = 0
  private proc: ChildProcess | null = null
  private stopping = false
  private partial = { out: "", err: "" }

  constructor(root: string, private emit: Emit) {
    this.status = { root, state: "idle", command: null, serial: null, startedAt: null, exitCode: null, errors: 0 }
  }

  get running() { return this.proc != null }

  async start(serial: string) {
    const cmd = detectRun(this.status.root, serial)
    this.lines = []
    this.stopping = false
    Object.assign(this.status, { state: "running", command: cmd.label, serial, startedAt: Date.now(), exitCode: null, errors: 0 })
    this.changed()
    this.push(`$ ${cmd.label}`, "info")
    const ios = isSimulator(serial)
    // A simulator shares the Mac's localhost: no reverse needed.
    if (cmd.kind !== "gradle" && !ios) {
      try { await reverse(serial); this.push("adb reverse tcp:8081 → the app reaches Metro on the Mac", "info") }
      catch (e: any) { this.push(`adb reverse failed: ${e.message}`, "warn") }
    }
    const adbBin = findAdb()
    const proc = spawn(cmd.bin, cmd.args, {
      cwd: cmd.cwd,
      detached: true,
      stdio: ["ignore", "pipe", "pipe"],
      env: {
        ...process.env,
        // CocoaPods refuses to run without a UTF-8 locale (launchd jobs have none).
        ...(ios ? { LANG: process.env.LANG || "en_US.UTF-8" } : { ANDROID_SERIAL: serial }),
        FORCE_COLOR: "0",
        // Expo/RN CLIs and Gradle look for the SDK here; launchd-started helpers often lack it.
        ...(adbBin && !process.env.ANDROID_HOME ? { ANDROID_HOME: join(adbBin, "..", "..") } : {}),
      },
    })
    this.proc = proc
    proc.stdout!.on("data", (c: Buffer) => this.read(c, false))
    proc.stderr!.on("data", (c: Buffer) => this.read(c, true))
    proc.on("error", (e) => this.push(`Couldn't start: ${e.message}`, "error"))
    proc.on("exit", (code) => {
      for (const k of ["out", "err"] as const) if (this.partial[k]) { this.line(this.partial[k], k === "err"); this.partial[k] = "" }
      if (this.proc !== proc) return
      this.proc = null
      const state = this.stopping ? "stopped" : code === 0 ? "succeeded" : "failed"
      Object.assign(this.status, { state, exitCode: code })
      this.push(state === "succeeded" ? "Build & run finished" : state === "stopped" ? "Stopped" : `Failed (exit ${code})`, state === "failed" ? "error" : "info")
      this.changed()
    })
    return { ...this.status }
  }

  stop(): Promise<void> {
    const proc = this.proc
    if (!proc?.pid) return Promise.resolve()
    this.stopping = true
    return new Promise((done) => {
      const t = setTimeout(() => { try { process.kill(-proc.pid!, "SIGKILL") } catch { /* gone */ } }, 4000)
      proc.once("exit", () => { clearTimeout(t); done() })
      try { process.kill(-proc.pid!, "SIGTERM") } catch { proc.kill("SIGTERM") }
    })
  }

  private read(chunk: Buffer, stderr: boolean) {
    const key = stderr ? "err" : "out"
    const parts = (this.partial[key] + chunk.toString("utf8")).split(/\r\n|\n|\r/)
    this.partial[key] = parts.pop() ?? ""
    for (const p of parts) this.line(p, stderr)
  }

  private line(raw: string, stderr: boolean) {
    const text = raw.replace(ANSI, "").replace(/\s+$/, "")
    if (!text.trim()) return
    // Gradle writes progress and warnings to stderr; only real failures should be red.
    let level = classify(text, false)
    if (stderr && level === "info" && /^w:|warning/i.test(text)) level = "warn"
    if (/^BUILD SUCCESSFUL|^Installing|Installed on \d+ device|Starting: Intent|› Opening/i.test(text)) level = "info"
    this.push(text, level)
  }

  private push(text: string, level: LogLevel) {
    const line = { seq: ++this.seq, level, text }
    this.lines.push(line)
    if (this.lines.length > 3000) this.lines.splice(0, this.lines.length - 3000)
    if (level === "error") this.status.errors++
    this.emit("log", this.status.root, { line })
  }

  private changed() { this.emit("status", this.status.root, { ...this.status }) }
}

export class RunManager {
  private tasks = new Map<string, RunTask>()
  private listeners = new Set<Emit>()

  subscribe(l: Emit) { this.listeners.add(l); return () => { this.listeners.delete(l) } }

  private task(root: string) {
    let t = this.tasks.get(root)
    if (!t) { t = new RunTask(root, (e, r, p) => { for (const l of this.listeners) l(e, r, p) }); this.tasks.set(root, t) }
    return t
  }

  status(root: string, tail = 500) {
    const t = this.tasks.get(root)
    let detected: string | null = null
    let detectError: string | null = null
    try { detected = detectRun(root, "<device>", false).label } catch (e: any) { detectError = e.message }
    let detectedIos: string | null = null
    let detectIosError: string | null = null
    try { detectedIos = detectRun(root, "<device>", true).label } catch (e: any) { detectIosError = e.message }
    const status = t?.status ?? { root, state: "idle", command: null, serial: null, startedAt: null, exitCode: null, errors: 0 }
    return { ...status, detected, detectError, detectedIos, detectIosError, lines: (t?.lines ?? []).slice(-tail) }
  }

  async start(root: string, serial: string) {
    const t = this.task(root)
    if (t.running) throw new Error("A build is already running for this project")
    return t.start(serial)
  }

  async stop(root: string) {
    const t = this.tasks.get(root)
    if (t) await t.stop()
    return t ? { ...t.status } : null
  }

  async stopAll() { await Promise.all([...this.tasks.values()].map((t) => t.stop())) }
}

// ── Logcat ──────────────────────────────────────────────────────────────────────────────────────

export type LogcatMode = "app" | "errors" | "all"
const FILTERS: Record<LogcatMode, string[]> = {
  // React Native's JS console + native RN + crashes.
  app: ["ReactNativeJS:V", "ReactNative:V", "AndroidRuntime:E", "Expo:V", "*:S"],
  errors: ["*:E"],
  all: ["*:I"],
}

/** threadtime: "11-18 17:01:02.123  1234  1250 E Tag     : message" → level. */
export function logcatLevel(text: string): LogLevel {
  const m = /^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+\d+\s+\d+\s+([VDIWEF])\s/.exec(text)
  const l = m?.[1]
  if (l === "E" || l === "F" || /FATAL EXCEPTION/.test(text)) return "error"
  if (l === "W") return "warn"
  return "info"
}

/**
 * One `adb logcat` stream, batched (every 250 ms, at most 300 lines per batch — the rest are counted
 * as skipped) so a chatty device can't flood a phone on 4G.
 */
export class LogcatStream {
  private proc: ChildProcess | null = null
  private batch: { level: LogLevel; text: string }[] = []
  private skipped = 0
  private partial = ""
  private timer: NodeJS.Timeout

  constructor(readonly id: string, serial: string, mode: LogcatMode, private send: (lines: unknown[], skipped: number) => void, private onExit: (code: number | null) => void) {
    const bin = findAdb()
    if (!bin) throw new Error("adb not found — install Android platform-tools on the Mac")
    // Backlog first: `-T N` counts lines *before* the tag filter, so a quiet app's JS lines fall out
    // of it. `-d` with the filter gives every matching line still in the buffer; keep the last 200.
    execFile(bin, ["-s", serial, "logcat", "-d", "-v", "threadtime", ...FILTERS[mode]], { timeout: 10_000, maxBuffer: 16 * 1024 * 1024 }, (_err, stdout) => {
      const backlog = String(stdout ?? "").split(/\r?\n/).filter((t) => t.trim() && !t.startsWith("--------- beginning of")).slice(-200)
      if (backlog.length) this.send(backlog.map((text) => ({ level: logcatLevel(text), text })), 0)
    })
    this.proc = spawn(bin, ["-s", serial, "logcat", "-v", "threadtime", "-T", "1", ...FILTERS[mode]], { stdio: ["ignore", "pipe", "pipe"] })
    this.proc.stdout!.on("data", (c: Buffer) => this.read(c))
    this.proc.stderr!.on("data", (c: Buffer) => this.read(c))
    this.proc.on("exit", (code) => { this.flush(); clearInterval(this.timer); this.proc = null; this.onExit(code) })
    this.timer = setInterval(() => this.flush(), 250)
  }

  private read(chunk: Buffer) {
    const parts = (this.partial + chunk.toString("utf8")).split(/\r?\n/)
    this.partial = parts.pop() ?? ""
    for (const text of parts) {
      if (!text.trim() || text.startsWith("--------- beginning of")) continue
      if (this.batch.length >= 300) { this.skipped++; continue }
      this.batch.push({ level: logcatLevel(text), text })
    }
  }

  private flush() {
    if (!this.batch.length && !this.skipped) return
    this.send(this.batch, this.skipped)
    this.batch = []
    this.skipped = 0
  }

  stop() {
    clearInterval(this.timer)
    this.proc?.kill("SIGTERM")
    this.proc = null
  }
}
