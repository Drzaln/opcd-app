// The Mac's Android emulators: list AVDs, start one (through `avdslim` when it's installed — low-memory
// host flags + guest slimming after boot — else the SDK `emulator`), stop it. Starting belongs to the
// helper, like builds: the log streams to every phone as `emu.log` / `emu.status`.
import { execFile, spawn } from "node:child_process"
import { existsSync } from "node:fs"
import { homedir, userInfo } from "node:os"
import { join } from "node:path"
import { findAdb } from "./adb.ts"

export function findAvdslim(): string | null {
  for (const p of [process.env.OC_HELPER_AVDSLIM, "/opt/homebrew/bin/avdslim", "/usr/local/bin/avdslim"]) if (p && existsSync(p)) return p
  for (const dir of (process.env.PATH ?? "").split(":")) if (dir && existsSync(join(dir, "avdslim"))) return join(dir, "avdslim")
  return null
}

export function findEmulator(): string | null {
  for (const p of [
    process.env.OC_HELPER_EMULATOR,
    process.env.ANDROID_HOME && join(process.env.ANDROID_HOME, "emulator", "emulator"),
    process.env.ANDROID_SDK_ROOT && join(process.env.ANDROID_SDK_ROOT, "emulator", "emulator"),
    join(homedir(), "Library", "Android", "sdk", "emulator", "emulator"),
  ]) if (p && existsSync(p)) return p
  return null
}

/**
 * AVDs live under the *user's* home (~/.android/avd), whatever HOME the helper was started with
 * (launchd jobs and tests can differ), so emulator/avdslim get the account's real home.
 */
function emuEnv(): NodeJS.ProcessEnv {
  const home = userInfo().homedir
  return { ...process.env, HOME: home, ANDROID_AVD_HOME: process.env.ANDROID_AVD_HOME ?? join(home, ".android", "avd") }
}

const run = (bin: string, args: string[], timeoutMs = 15_000) =>
  new Promise<string>((resolve) => execFile(bin, args, { timeout: timeoutMs, env: emuEnv() }, (_e, so) => resolve(String(so ?? ""))))

/** Like `run`, with stdin closed (EOF answers prompts with their default). */
function runNoInput(bin: string, args: string[], timeoutMs: number): Promise<string> {
  return new Promise((resolve) => {
    const p = spawn(bin, args, { stdio: ["ignore", "pipe", "pipe"], env: emuEnv() })
    let out = ""
    p.stdout!.on("data", (c) => { out += c })
    p.stderr!.on("data", (c) => { out += c })
    const t = setTimeout(() => p.kill("SIGTERM"), timeoutMs)
    p.on("close", () => { clearTimeout(t); resolve(out) })
    p.on("error", () => { clearTimeout(t); resolve(out) })
  })
}

export interface Avd { name: string; serial: string | null }

/** AVDs from `emulator -list-avds`, each with the serial it runs as (via `adb emu avd name`). */
export async function listAvds(): Promise<{ avds: Avd[]; avdslim: boolean; emulator: boolean }> {
  const emulator = findEmulator()
  const names = emulator ? (await run(emulator, ["-list-avds"])).split("\n").map((s) => s.trim()).filter((s) => /^[\w.\-]+$/.test(s)) : []
  const running = new Map<string, string>()
  const adb = findAdb()
  if (adb) {
    const serials = (await run(adb, ["devices"])).split("\n").map((l) => /^(emulator-\d+)\s+device/.exec(l)?.[1]).filter(Boolean) as string[]
    for (const serial of serials) {
      const name = (await run(adb, ["-s", serial, "emu", "avd", "name"], 5000)).split("\n")[0]?.trim()
      if (name) running.set(name, serial)
    }
  }
  return { avds: names.map((name) => ({ name, serial: running.get(name) ?? null })), avdslim: findAvdslim() != null, emulator: emulator != null }
}

export interface StartOptions { headless: boolean; cold: boolean; ram: number; slim: boolean }

export interface EmuStatus { avd: string; state: "starting" | "booted" | "failed" | "stopped"; via: "avdslim" | "emulator"; message: string | null }
type Emit = (event: "log" | "status", payload: unknown) => void

export class EmulatorManager {
  private status = new Map<string, EmuStatus>()
  private listeners = new Set<Emit>()

  subscribe(l: Emit) { this.listeners.add(l); return () => { this.listeners.delete(l) } }
  private emit(e: "log" | "status", p: unknown) { for (const l of this.listeners) l(e, p) }

  statuses() { return [...this.status.values()] }

  async start(avd: string, opts: StartOptions) {
    const { avds } = await listAvds()
    const known = avds.find((a) => a.name === avd)
    if (!known) throw new Error(`No AVD named ${avd}`)
    if (known.serial) throw new Error(`${avd} is already running (${known.serial})`)
    if (this.status.get(avd)?.state === "starting") throw new Error(`${avd} is already starting`)
    const ram = Math.min(8192, Math.max(1024, Math.round(opts.ram || 1536)))
    const avdslim = findAvdslim()
    const emulator = findEmulator()
    let bin: string
    let args: string[]
    let via: EmuStatus["via"]
    if (avdslim) {
      // Always name the AVD: without it avdslim asks (and, with no terminal, takes the first one).
      bin = avdslim
      args = ["start", avd, `--ram=${ram}`, ...(opts.headless ? ["--headless"] : []), ...(opts.cold ? ["--cold"] : []), ...(opts.slim ? [] : ["--no-slim"])]
      via = "avdslim"
    } else if (emulator) {
      bin = emulator
      args = ["-avd", avd, "-memory", String(ram), "-no-audio", "-no-boot-anim", ...(opts.headless ? ["-no-window"] : []), ...(opts.cold ? ["-no-snapshot-load"] : [])]
      via = "emulator"
    } else throw new Error("The Android emulator isn't installed on the Mac")

    const status: EmuStatus = { avd, state: "starting", via, message: null }
    this.status.set(avd, status)
    this.emit("status", { ...status })
    const say = (text: string) => this.emit("log", { avd, text })
    say(`$ ${via} ${args.join(" ")}`)
    const proc = spawn(bin, args, { detached: true, stdio: ["ignore", "pipe", "pipe"], env: emuEnv() })
    let partial = ""
    const read = (c: Buffer) => {
      const parts = (partial + String(c)).split(/\r?\n/)
      partial = parts.pop() ?? ""
      for (const p of parts) if (p.trim()) say(p.replace(/\u001b\[[0-9;]*m/g, "").trimEnd())
    }
    proc.stdout!.on("data", read)
    proc.stderr!.on("data", read)
    const finish = (state: EmuStatus["state"], message: string | null) => {
      if (status.state !== "starting") return
      Object.assign(status, { state, message })
      this.emit("status", { ...status })
    }
    if (via === "avdslim") {
      // avdslim waits for boot, slims, then exits; the emulator keeps running on its own.
      // With --no-slim it exits right after spawning, so confirm the boot ourselves either way.
      proc.on("exit", (code) => {
        if (code !== 0) return finish("failed", `avdslim exited with code ${code}`)
        this.waitForBoot(avd).then((ok) => finish(ok ? "booted" : "failed", ok ? null : "Didn't boot within 3 minutes"))
      })
    } else {
      // The emulator process *is* the device: detach it and watch adb for boot_completed.
      proc.unref()
      proc.on("exit", (code) => finish("failed", `emulator exited with code ${code}`))
      this.waitForBoot(avd).then((ok) => finish(ok ? "booted" : "failed", ok ? null : "Didn't boot within 3 minutes"))
    }
    return { ...status }
  }

  private async waitForBoot(avd: string): Promise<boolean> {
    const adb = findAdb()
    if (!adb) return false
    for (let i = 0; i < 90; i++) {
      await new Promise((r) => setTimeout(r, 2000))
      const serial = (await listAvds()).avds.find((a) => a.name === avd)?.serial
      if (serial && (await run(adb, ["-s", serial, "shell", "getprop", "sys.boot_completed"], 5000)).trim() === "1") return true
    }
    return false
  }

  async stop(serial: string) {
    if (!/^emulator-\d+$/.test(serial)) throw new Error("Only emulators can be stopped here")
    const avdslim = findAvdslim()
    const adb = findAdb()
    // avdslim asks "save a Golden Snapshot first? [y/N]": stdin must be closed so it takes the
    // default instead of waiting (execFile leaves stdin open).
    const out = avdslim ? await runNoInput(avdslim, ["stop", serial], 60_000) : adb ? await run(adb, ["-s", serial, "emu", "kill"]) : ""
    // Don't report success until adb agrees.
    for (let i = 0; i < 20; i++) {
      const listed = adb ? (await run(adb, ["devices"])).split("\n").some((l) => l.startsWith(serial + "\t")) : false
      if (!listed) return { output: out.trim().split("\n").slice(-1)[0] ?? "" }
      await new Promise((r) => setTimeout(r, 1000))
    }
    throw new Error(`${serial} is still running${out.trim() ? `: ${out.trim().split("\n").slice(-1)[0]}` : ""}`)
  }
}
