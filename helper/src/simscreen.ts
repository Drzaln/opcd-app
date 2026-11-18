// Live screen of an iOS simulator, through Meta's idb companion (`brew install facebook/fb/idb-companion`):
// the helper runs one `idb_companion` per stream on a private unix socket and talks gRPC to it — video
// as H.264 (one access unit per message, hardware-encoded on the Mac) and touches over a persistent
// HID stream, so a drag is a series of touch-downs sent as they happen, not a process per event.
// Frames go to the phone in the same binary format as the Android (scrcpy) screen.
import { spawn, type ChildProcess } from "node:child_process"
import { existsSync, rmSync } from "node:fs"
import { tmpdir, userInfo } from "node:os"
import { join } from "node:path"
import { fileURLToPath } from "node:url"
import { randomBytes } from "node:crypto"
import * as grpc from "@grpc/grpc-js"
import * as loader from "@grpc/proto-loader"
import { framePacket, KIND_CONFIG, KIND_FRAME, KIND_KEYFRAME, type ScreenOptions } from "./screen.ts"

export function findIdbCompanion(): string | null {
  for (const p of [process.env.OC_HELPER_IDB_COMPANION, "/opt/homebrew/bin/idb_companion", "/usr/local/bin/idb_companion"]) if (p && existsSync(p)) return p
  for (const dir of (process.env.PATH ?? "").split(":")) if (dir && existsSync(join(dir, "idb_companion"))) return join(dir, "idb_companion")
  return null
}

/** helper/proto/idb.proto, next to src/ and dist/. */
function protoPath(): string {
  for (const rel of ["../proto/idb.proto", "../../proto/idb.proto"]) {
    const p = fileURLToPath(new URL(rel, import.meta.url))
    if (existsSync(p)) return p
  }
  throw new Error("helper/proto/idb.proto is missing")
}

let service: grpc.ServiceClientConstructor | null = null
function companionService(): grpc.ServiceClientConstructor {
  if (!service) {
    const def = loader.loadSync(protoPath(), { keepCase: true, longs: Number, enums: String, defaults: true, oneofs: true })
    service = (grpc.loadPackageDefinition(def) as any).idb.CompanionService as grpc.ServiceClientConstructor
  }
  return service
}

/** Splits an Annex B access unit into NAL units (start code included). */
export function nalUnits(au: Buffer): Buffer[] {
  const starts: number[] = []
  for (let i = 0; i + 3 < au.length; i++) {
    if (au[i] === 0 && au[i + 1] === 0 && (au[i + 2] === 1 || (au[i + 2] === 0 && au[i + 3] === 1))) {
      starts.push(i)
      i += au[i + 2] === 1 ? 2 : 3
    }
  }
  if (!starts.length) return [au]
  return starts.map((s, k) => au.subarray(s, k + 1 < starts.length ? starts[k + 1] : au.length))
}

const nalType = (nal: Buffer) => nal[nal[2] === 1 ? 3 : 4] & 0x1f

/**
 * One idb message (an access unit) → the app's packets: SPS + PPS as a config packet (kind 1, only
 * when they change), the rest as a frame (kind 3 with an IDR slice, else kind 2).
 */
export class AccessUnitPacker {
  private config: Buffer | null = null
  constructor(private out: (kind: number, data: Buffer) => void) {}

  push(au: Buffer) {
    const params: Buffer[] = []
    const rest: Buffer[] = []
    let key = false
    for (const nal of nalUnits(au)) {
      const t = nalType(nal)
      if (t === 7 || t === 8) params.push(nal)
      else { if (t === 5) key = true; rest.push(nal) }
    }
    if (params.length) {
      const cfg = Buffer.concat(params)
      if (!this.config || !this.config.equals(cfg)) { this.config = cfg; this.out(KIND_CONFIG, cfg) }
    }
    if (rest.length) this.out(key ? KIND_KEYFRAME : KIND_FRAME, Buffer.concat(rest))
  }
}

export class SimScreenSession {
  width = 0
  height = 0
  deviceName = ""
  private proc: ChildProcess | null = null
  private client: grpc.Client | null = null
  private video: grpc.ClientDuplexStream<any, any> | null = null
  private hid: grpc.ClientWritableStream<any> | null = null
  private sock = join(tmpdir(), `oc-idb-${randomBytes(4).toString("hex")}.sock`)
  private closed = false
  /** Why the stream ended (shown instead of "Screen stopped" when it dies while starting). */
  private endReason: string | null = null
  private points = { w: 0, h: 0 }
  /** The pointer id that owns the (single) simulated finger. */
  private finger: number | null = null
  private log: string[] = []
  private t0 = process.hrtime.bigint()

  constructor(
    readonly udid: string,
    private onPacket: (packet: Buffer) => boolean,
    private onEnd: (reason: string) => void,
  ) {}

  private alive() { if (this.closed) throw new Error(this.endReason ?? "Screen stopped") }

  async start(opts: ScreenOptions) {
    const bin = findIdbCompanion()
    if (!bin) throw new Error("The live iOS screen needs idb on the Mac: brew install facebook/fb/idb-companion")
    const proc = spawn(bin, ["--udid", this.udid, "--grpc-domain-sock", this.sock, "--terminate-offline", "true", "--log-level", "info"], {
      stdio: ["ignore", "pipe", "pipe"],
      env: { ...process.env, HOME: userInfo().homedir },
    })
    this.proc = proc
    const keep = (c: Buffer) => { for (const l of String(c).split("\n")) if (l.trim()) { this.log.push(l.trim()); if (this.log.length > 20) this.log.shift() } }
    proc.stdout!.on("data", keep)
    proc.stderr!.on("data", keep)
    proc.on("exit", (code) => this.end(code === 0 ? "The simulator's screen stream ended" : `idb_companion exited (${code}): ${this.log.slice(-2).join(" · ")}`))
    console.log(`  screen ${this.udid}: idb_companion pid ${proc.pid}`)

    // The socket appears once the gRPC server is up.
    for (let i = 0; i < 100 && !existsSync(this.sock); i++) { this.alive(); await new Promise((r) => setTimeout(r, 100)) }
    this.alive()
    if (!existsSync(this.sock)) { const why = this.log.slice(-2).join(" · "); this.stop(); throw new Error(`idb_companion didn't start${why ? `: ${why}` : ""}`) }

    const Service = companionService()
    const client = new Service(`unix:${this.sock}`, grpc.credentials.createInsecure()) as any
    this.client = client
    const desc: any = await new Promise((resolve, reject) => client.describe({}, (e: Error | null, r: any) => (e ? reject(e) : resolve(r))))
    this.alive()
    const dims = desc?.target_description?.screen_dimensions ?? {}
    this.points = { w: Number(dims.width_points) || 0, h: Number(dims.height_points) || 0 }
    this.deviceName = String(desc?.target_description?.name ?? "")
    const px = { w: Number(dims.width) || 0, h: Number(dims.height) || 0 }
    const scale = px.h ? Math.min(1, opts.maxSize / Math.max(px.w, px.h)) : 0.5
    this.width = Math.round(px.w * scale)
    this.height = Math.round(px.h * scale)

    this.hid = client.hid((e: grpc.ServiceError | null) => { if (e && !this.closed) this.log.push(`hid: ${e.message}`) })
    const packer = new AccessUnitPacker((kind, data) => {
      const pts = kind === KIND_CONFIG ? 0n : (process.hrtime.bigint() - this.t0) / 1000n
      // Back-pressure (4G): stop reading until the socket drains rather than dropping frames.
      if (!this.onPacket(framePacket(kind, pts, data)) && this.video) {
        const v = this.video
        v.pause()
        setTimeout(() => !this.closed && v.resume(), 100)
      }
    })
    const video = client.video_stream()
    this.video = video
    let first: (() => void) | null = null
    const firstFrame = new Promise<void>((resolve) => { first = resolve })
    video.on("data", (r: any) => {
      const data: Buffer | undefined = r?.payload?.data
      if (data?.length) { packer.push(Buffer.from(data)); first?.(); first = null }
    })
    video.on("error", (e: Error) => this.end(`Screen stream failed: ${e.message}`))
    video.on("end", () => this.end("The simulator's screen stream ended"))
    video.write({ start: { fps: opts.maxFps, format: "H264", compression_quality: 0.2, scale_factor: scale, avg_bitrate: opts.bitRate, key_frame_rate: 0 } })
    const timeout = new Promise<"timeout">((r) => setTimeout(() => r("timeout"), 10_000))
    if ((await Promise.race([firstFrame, timeout])) === "timeout") { this.stop(); throw new Error("The simulator's screen stream didn't start (is it booted?)") }
    this.alive()
    console.log(`  screen ${this.udid}: streaming ${this.width}×${this.height} via idb`)
    return { width: this.width, height: this.height, deviceName: this.deviceName }
  }

  private press(x: number, y: number, up: boolean) {
    this.hid?.write({ press: { action: { touch: { point: { x, y } } }, direction: up ? "UP" : "DOWN" } })
  }

  /** action 0 down, 1 up, 2 move, in the app's w×h frame → points. One finger: the first pointer down. */
  touch(action: number, pointer: number, x: number, y: number, w: number, h: number) {
    if (!this.hid || !this.points.w || !(w > 0) || !(h > 0)) return
    const px = Math.min(this.points.w, Math.max(0, (x / w) * this.points.w))
    const py = Math.min(this.points.h, Math.max(0, (y / h) * this.points.h))
    if (action === 0) {
      if (this.finger != null) return
      this.finger = pointer
      this.press(px, py, false)
    } else if (pointer === this.finger) {
      if (action === 2) this.press(px, py, false)
      else if (action === 1) { this.press(px, py, true); this.finger = null }
    }
  }

  /** A drag along a path (points), for the gestures iOS has instead of Back / Recents buttons. */
  private async gesture(path: [number, number][], holdMs = 0) {
    const [sx, sy] = path[0]
    this.press(sx, sy, false)
    for (const [x, y] of path.slice(1)) { await new Promise((r) => setTimeout(r, 16)); this.press(x, y, false) }
    if (holdMs) await new Promise((r) => setTimeout(r, holdMs))
    const [ex, ey] = path[path.length - 1]
    this.press(ex, ey, true)
  }

  private line(x0: number, y0: number, x1: number, y1: number, steps: number): [number, number][] {
    return Array.from({ length: steps + 1 }, (_, i) => [x0 + ((x1 - x0) * i) / steps, y0 + ((y1 - y0) * i) / steps] as [number, number])
  }

  /** home → Home; back → swipe from the left edge; recents → swipe up from the bottom and hold (app switcher); menu → shake (React Native dev menu). */
  key(name: string) {
    if (!this.hid || this.finger != null) return
    const { w, h } = this.points
    if (name === "home") {
      this.hid.write({ press: { action: { button: { button: "HOME" } }, direction: "DOWN" } })
      this.hid.write({ press: { action: { button: { button: "HOME" } }, direction: "UP" } })
    } else if (name === "menu") this.hid.write({ shake: {} })
    else if (name === "back" && w) void this.gesture(this.line(1, h * 0.45, w * 0.7, h * 0.45, 12))
    else if (name === "recents") {
      // A double Home press opens the app switcher (an edge swipe over HID scrolls the app instead).
      const press = () => {
        this.hid?.write({ press: { action: { button: { button: "HOME" } }, direction: "DOWN" } })
        this.hid?.write({ press: { action: { button: { button: "HOME" } }, direction: "UP" } })
      }
      press()
      setTimeout(press, 120)
    }
  }

  resize(width: number, height: number) {
    if (width > 0 && height > 0) { this.width = width; this.height = height }
  }

  private end(reason: string) {
    if (this.closed) return
    this.endReason = reason
    this.stop()
    this.onEnd(reason)
  }

  stop() {
    if (this.closed) return
    this.closed = true
    console.log(`  screen ${this.udid}: stopped (idb_companion pid ${this.proc?.pid ?? "-"})`)
    try { this.video?.write({ stop: {} }); this.video?.cancel() } catch { /* closing */ }
    try { this.hid?.end() } catch { /* closing */ }
    this.client?.close()
    this.proc?.kill("SIGTERM")
    rmSync(this.sock, { force: true })
  }
}
