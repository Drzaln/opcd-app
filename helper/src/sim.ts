// The Mac's iOS simulators: list, start (through `simslim` when it's installed — the simulator boots
// with ~170 background daemons turned off, about 4x less memory), stop, and their log. Like Android
// emulators, starting belongs to the helper and streams to every phone as `sim.log` / `sim.status`.
// Build & run for iOS is RunManager's (adb.ts) with a simulator UDID as the "serial".
import { execFile, execFileSync, spawn, type ChildProcess } from "node:child_process"
import { existsSync } from "node:fs"
import { userInfo } from "node:os"
import { join } from "node:path"
import type { LogLevel } from "./metro.ts"

export const UDID = /^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}$/i
export const isSimulator = (serial: string) => UDID.test(serial)

export function findSimslim(): string | null {
  for (const p of [process.env.OC_HELPER_SIMSLIM, "/opt/homebrew/bin/simslim", "/usr/local/bin/simslim"]) if (p && existsSync(p)) return p
  for (const dir of (process.env.PATH ?? "").split(":")) if (dir && existsSync(join(dir, "simslim"))) return join(dir, "simslim")
  return null
}

let xcrunCache: { bin: string | null; at: number } | null = null
/** `xcrun`, if it can find `simctl` (needs Xcode, not just the Command Line Tools). Cached for a minute. */
export function findXcrun(): string | null {
  if (xcrunCache && Date.now() - xcrunCache.at < 60_000) return xcrunCache.bin
  let bin: string | null = process.env.OC_HELPER_XCRUN ?? "/usr/bin/xcrun"
  if (!existsSync(bin)) bin = null
  else if (!process.env.OC_HELPER_XCRUN) {
    try { execFileSync(bin, ["--find", "simctl"], { timeout: 5000, stdio: "ignore" }) } catch { bin = null }
  }
  xcrunCache = { bin, at: Date.now() }
  return bin
}

/** simctl/simslim look for the device set under the account's real home, whatever HOME the helper has. */
function simEnv(): NodeJS.ProcessEnv {
  return { ...process.env, HOME: userInfo().homedir }
}

function run(bin: string, args: string[], timeoutMs = 20_000): Promise<{ ok: boolean; out: string }> {
  return new Promise((resolve) => execFile(bin, args, { timeout: timeoutMs, env: simEnv(), maxBuffer: 16 * 1024 * 1024 }, (e, so, se) =>
    resolve({ ok: !e, out: `${so ?? ""}${e ? se ?? "" : ""}` })))
}

export interface Sim {
  udid: string
  name: string
  runtime: string
  state: string
  booted: boolean
  /** true / false once booted (simslim reads the state of a running simulator); null when unknown. */
  slim: boolean | null
  memory: number | null
}

/** iOS simulators (iPhone and iPad; not watch/TV) from `simctl list -j`, plus simslim's view of booted ones. */
export async function listSims(): Promise<{ sims: Sim[]; simslim: boolean; xcode: boolean }> {
  const xcrun = findXcrun()
  const simslim = findSimslim()
  if (!xcrun) return { sims: [], simslim: simslim != null, xcode: false }
  const res = await run(xcrun, ["simctl", "list", "devices", "available", "-j"])
  const sims: Sim[] = []
  try {
    const devices = JSON.parse(res.out).devices as Record<string, any[]>
    for (const [runtimeId, list] of Object.entries(devices)) {
      const m = /SimRuntime\.iOS-(\d+)-(\d+)/.exec(runtimeId)
      if (!m) continue
      for (const d of list) {
        if (!UDID.test(String(d.udid)) || d.isAvailable === false) continue
        sims.push({ udid: d.udid, name: String(d.name), runtime: `iOS ${m[1]}.${m[2]}`, state: String(d.state), booted: d.state === "Booted", slim: null, memory: null })
      }
    }
  } catch { /* no simulators */ }
  if (simslim && sims.some((s) => s.booted)) {
    const sl = await run(simslim, ["list", "--json", "--booted"], 30_000)
    try {
      for (const d of JSON.parse(sl.out) as any[]) {
        const s = sims.find((x) => x.udid === d.udid)
        if (!s) continue
        if (typeof d.managedDisabled === "number") s.slim = d.managedDisabled > 0
        if (typeof d.memory?.bytes === "number") s.memory = d.memory.bytes
      }
    } catch { /* simslim too old for --booted / --json */ }
  }
  // Newest runtime first, booted on top, then by name.
  sims.sort((a, b) => Number(b.booted) - Number(a.booted) || b.runtime.localeCompare(a.runtime, undefined, { numeric: true }) || a.name.localeCompare(b.name))
  return { sims, simslim: simslim != null, xcode: true }
}

export interface SimStartOptions { slim: boolean; window: boolean }
export interface SimStatus { udid: string; name: string; state: "starting" | "booted" | "failed" | "stopped"; via: "simslim" | "simctl"; message: string | null }
type Emit = (event: "log" | "status", payload: unknown) => void

export class SimulatorManager {
  private status = new Map<string, SimStatus>()
  private listeners = new Set<Emit>()

  subscribe(l: Emit) { this.listeners.add(l); return () => { this.listeners.delete(l) } }
  private emit(e: "log" | "status", p: unknown) { for (const l of this.listeners) l(e, p) }
  statuses() { return [...this.status.values()] }

  /**
   * Boots a simulator. With simslim and `slim`: `simslim on` (reconfigures while off, boots slim).
   * With simslim and not `slim`: `simslim off` first when it was slimmed, so it boots stock.
   * Without simslim: `simctl boot` + `simctl bootstatus`. `window` opens Simulator.app on it.
   */
  async start(udid: string, opts: SimStartOptions) {
    if (!UDID.test(udid)) throw new Error("Pick a simulator")
    const xcrun = findXcrun()
    if (!xcrun) throw new Error("Xcode isn't installed on the Mac (or xcode-select points at the Command Line Tools)")
    const { sims } = await listSims()
    const sim = sims.find((s) => s.udid === udid)
    if (!sim) throw new Error("No such simulator")
    if (sim.booted) throw new Error(`${sim.name} is already running`)
    if (this.status.get(udid)?.state === "starting") throw new Error(`${sim.name} is already starting`)
    const simslim = findSimslim()
    const via: SimStatus["via"] = simslim ? "simslim" : "simctl"
    const status: SimStatus = { udid, name: sim.name, state: "starting", via, message: null }
    this.status.set(udid, status)
    this.emit("status", { ...status })
    const say = (text: string) => this.emit("log", { udid, name: sim.name, text })

    const steps: [string, string[], number][] = simslim
      ? opts.slim
        ? [[simslim, ["on", udid], 15 * 60_000]]
        : [[simslim, ["off", udid], 15 * 60_000], [simslim, ["boot", udid], 5 * 60_000]]
      : [[xcrun, ["simctl", "boot", udid], 2 * 60_000], [xcrun, ["simctl", "bootstatus", udid, "-b"], 5 * 60_000]]
    ;(async () => {
      for (const [bin, args, timeout] of steps) {
        say(`$ ${bin === xcrun ? "xcrun" : "simslim"} ${args.join(" ")}`)
        const code = await stream(bin, args, timeout, say)
        if (code !== 0) {
          // `simslim off` on a stock simulator may refuse; booting it is still what was asked.
          if (args[0] === "off") { say("(already stock)"); continue }
          return this.finish(status, "failed", `${args[0]} exited with code ${code}`)
        }
      }
      if (opts.window) {
        say("$ open -a Simulator")
        await new Promise((r) => execFile("open", ["-a", "Simulator", "--args", "-CurrentDeviceUDID", udid], { env: simEnv() }, r))
      }
      const booted = (await listSims()).sims.find((s) => s.udid === udid)?.booted
      this.finish(status, booted ? "booted" : "failed", booted ? null : "It didn't boot")
    })()
    return { ...status }
  }

  private finish(status: SimStatus, state: SimStatus["state"], message: string | null) {
    if (status.state !== "starting") return
    Object.assign(status, { state, message })
    this.emit("status", { ...status })
  }

  async stop(udid: string) {
    if (!UDID.test(udid)) throw new Error("Pick a simulator")
    const xcrun = findXcrun()
    if (!xcrun) throw new Error("Xcode isn't installed on the Mac")
    const simslim = findSimslim()
    const res = simslim ? await run(simslim, ["shutdown", udid], 90_000) : await run(xcrun, ["simctl", "shutdown", udid], 90_000)
    const still = (await listSims()).sims.find((s) => s.udid === udid)?.booted
    if (still) throw new Error(`The simulator is still running${res.out.trim() ? `: ${res.out.trim().split("\n").slice(-1)[0]}` : ""}`)
    const s = this.status.get(udid)
    if (s) { s.state = "stopped"; this.emit("status", { ...s }) }
    return { ok: true }
  }
}

/** Runs a command, sending its output line by line (stdin closed so prompts take their default). */
function stream(bin: string, args: string[], timeoutMs: number, say: (t: string) => void): Promise<number | null> {
  return new Promise((resolve) => {
    const p = spawn(bin, args, { stdio: ["ignore", "pipe", "pipe"], env: simEnv() })
    let partial = ""
    const read = (c: Buffer) => {
      const parts = (partial + String(c)).split(/\r?\n/)
      partial = parts.pop() ?? ""
      for (const l of parts) if (l.trim()) say(l.replace(/\u001b\[[0-9;]*m/g, "").trimEnd())
    }
    p.stdout!.on("data", read)
    p.stderr!.on("data", read)
    const t = setTimeout(() => p.kill("SIGTERM"), timeoutMs)
    p.on("close", (code) => { clearTimeout(t); if (partial.trim()) say(partial.trim()); resolve(code) })
    p.on("error", (e) => { clearTimeout(t); say(e.message); resolve(-1) })
  })
}

// ── Simulator log (the iOS counterpart of logcat) ────────────────────────────────────────────────

export type SimLogMode = "app" | "errors" | "all"
/** Processes launched from an installed app bundle (not the simulator's own daemons). */
const APPS = `processImagePath CONTAINS "/Containers/Bundle/Application/"`
const ERR = `(messageType == error OR messageType == fault)`
const PREDICATES: Record<SimLogMode, string> = {
  // React Native's JS console (RCTLog → os_log) plus anything the app logs as an error.
  app: `subsystem == "com.facebook.react.log" OR (${APPS} AND ${ERR})`,
  errors: `${APPS} AND ${ERR}`,
  all: APPS,
}

const COMPACT = /^\d{4}-\d\d-\d\d (\d\d:\d\d:\d\d)\.\d+ (\S+)\s+(.+?)\[\d+:[0-9a-f]+\] (?:\[([^\]]+)\] )?(.*)$/

/**
 * `log --style compact`: "2026-11-19 05:43:39.917 E  dasd[21924:1e1fa] [com.apple.dasd:main] text"
 * → "05:43:39 E dasd: [com.apple.dasd:main] text" (RN's JS lines lose their bracket: it's always the same).
 */
export function simLogLine(raw: string): { level: LogLevel; text: string } | null {
  const m = COMPACT.exec(raw)
  if (!m) return null
  const [, time, ty, proc, sub, msg] = m
  // os_log has no warning type: default/info/debug are info, error/fault are errors.
  const level: LogLevel = ty === "E" || ty.startsWith("F") ? "error" : "info"
  const letter = ty === "Df" ? "I" : ty === "Db" ? "D" : ty.charAt(0)
  const tag = sub && !sub.startsWith("com.facebook.react.log") ? `[${sub}] ` : ""
  return { level, text: `${time} ${letter} ${proc}: ${tag}${msg}` }
}

/** Same shape as LogcatStream (batched every 250 ms, at most 300 lines per batch). */
export class SimLogStream {
  private proc: ChildProcess | null = null
  private batch: { level: LogLevel; text: string }[] = []
  private skipped = 0
  private partial = ""
  private timer: NodeJS.Timeout

  constructor(readonly id: string, udid: string, mode: SimLogMode, private send: (lines: unknown[], skipped: number) => void, private onExit: (code: number | null) => void) {
    const xcrun = findXcrun()
    if (!xcrun) throw new Error("Xcode isn't installed on the Mac")
    if (!UDID.test(udid)) throw new Error("Pick a simulator")
    const predicate = PREDICATES[mode]
    // Backlog: the last 5 minutes that match, newest 200.
    execFile(xcrun, ["simctl", "spawn", udid, "log", "show", "--last", "5m", "--style", "compact", "--info", "--predicate", predicate],
      { timeout: 20_000, maxBuffer: 32 * 1024 * 1024, env: simEnv() }, (_e, so) => {
        const backlog = String(so ?? "").split("\n").map(simLogLine).filter(Boolean).slice(-200) as { level: LogLevel; text: string }[]
        if (backlog.length) this.send(backlog, 0)
      })
    this.proc = spawn(xcrun, ["simctl", "spawn", udid, "log", "stream", "--style", "compact", "--level", "info", "--predicate", predicate], { stdio: ["ignore", "pipe", "pipe"], env: simEnv() })
    this.proc.stdout!.on("data", (c: Buffer) => this.read(c))
    this.proc.on("exit", (code) => { this.flush(); clearInterval(this.timer); this.proc = null; this.onExit(code) })
    this.timer = setInterval(() => this.flush(), 250)
  }

  private read(chunk: Buffer) {
    const parts = (this.partial + chunk.toString("utf8")).split("\n")
    this.partial = parts.pop() ?? ""
    for (const raw of parts) {
      const line = simLogLine(raw)
      if (!line) continue
      if (this.batch.length >= 300) { this.skipped++; continue }
      this.batch.push(line)
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
