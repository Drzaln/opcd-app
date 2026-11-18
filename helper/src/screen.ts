// Live device screen through scrcpy-server (the one Homebrew's `scrcpy` ships): H.264 video from any
// adb device (emulator or phone) relayed to the app as binary WebSocket frames, and touch/keys sent
// back on scrcpy's control socket. No npm dependency: plain sockets + adb.
//
// Binary frame to the app:  [kind:1][ptsMicros:8][h264 bytes]   kind 1 = config (SPS/PPS),
//                                                                 2 = frame, 3 = key frame
import { execFile, spawn, type ChildProcess } from "node:child_process"
import { existsSync } from "node:fs"
import { createServer, connect, type Socket } from "node:net"
import { randomBytes } from "node:crypto"
import { findAdb } from "./adb.ts"

export const KIND_CONFIG = 1
export const KIND_FRAME = 2
export const KIND_KEYFRAME = 3

/** scrcpy-server from SCRCPY_SERVER_PATH or a Homebrew install. */
export function findScrcpyServer(): string | null {
  for (const p of [
    process.env.SCRCPY_SERVER_PATH,
    "/opt/homebrew/share/scrcpy/scrcpy-server",
    "/opt/homebrew/opt/scrcpy/share/scrcpy/scrcpy-server",
    "/usr/local/share/scrcpy/scrcpy-server",
  ]) if (p && existsSync(p)) return p
  return null
}

let cachedVersion: string | null = null
/** The server only talks to a client announcing its exact version. */
async function scrcpyVersion(): Promise<string> {
  if (process.env.SCRCPY_VERSION) return process.env.SCRCPY_VERSION
  if (cachedVersion) return cachedVersion
  for (const bin of ["scrcpy", "/opt/homebrew/bin/scrcpy", "/usr/local/bin/scrcpy"]) {
    const out = await new Promise<string>((r) => execFile(bin, ["--version"], { timeout: 5000 }, (_e, so) => r(String(so ?? ""))))
    const v = /scrcpy (\d+\.\d+(?:\.\d+)?)/.exec(out)?.[1]
    if (v) return (cachedVersion = v)
  }
  throw new Error("Couldn't read the scrcpy version (is `scrcpy` installed? brew install scrcpy)")
}

// ── Control messages (scrcpy protocol) ──────────────────────────────────────────────────────────

const TYPE_INJECT_KEYCODE = 0
const TYPE_INJECT_TOUCH = 2
export const KEYS: Record<string, number> = { back: 4, home: 3, recents: 187, menu: 82, power: 26, volumeUp: 24, volumeDown: 25 }

/** INJECT_TOUCH_EVENT: 32 bytes. action 0 down, 1 up, 2 move; coordinates in video pixels. */
export function touchMessage(action: number, pointerId: number, x: number, y: number, width: number, height: number): Buffer {
  const b = Buffer.alloc(32)
  b.writeUInt8(TYPE_INJECT_TOUCH, 0)
  b.writeUInt8(action, 1)
  b.writeBigUInt64BE(BigInt(pointerId) & 0xffffffffffffffffn, 2)
  b.writeInt32BE(Math.round(x), 10)
  b.writeInt32BE(Math.round(y), 14)
  b.writeUInt16BE(width & 0xffff, 18)
  b.writeUInt16BE(height & 0xffff, 20)
  b.writeUInt16BE(action === 1 ? 0 : 0xffff, 22) // pressure 1.0 (0 on up)
  b.writeUInt32BE(action === 1 ? 0 : 1, 24) // action button: primary
  b.writeUInt32BE(action === 1 ? 0 : 1, 28) // buttons
  return b
}

/** INJECT_KEYCODE: 14 bytes (down + up = a press). */
export function keyMessage(action: number, keycode: number): Buffer {
  const b = Buffer.alloc(14)
  b.writeUInt8(TYPE_INJECT_KEYCODE, 0)
  b.writeUInt8(action, 1)
  b.writeInt32BE(keycode, 2)
  b.writeInt32BE(0, 6) // repeat
  b.writeInt32BE(0, 10) // meta state
  return b
}

/**
 * Splits scrcpy's (5.x) video stream, after the 4-byte codec id, into 12-byte-headed packets:
 *  - session: bit 63 set → [flags:4][width:4][height:4], no payload (start, and every rotation)
 *  - media:   [pts+flags:8][size:4][data], bit 62 = config (SPS/PPS), bit 61 = key frame
 * (scrcpy 3 had no session packets and used bits 63/62 for config/key.)
 */
export class PacketParser {
  private buf: Buffer = Buffer.alloc(0)
  constructor(
    private onPacket: (kind: number, ptsMicros: bigint, data: Buffer) => void,
    private onSession: (width: number, height: number) => void = () => {},
  ) {}

  push(chunk: Buffer) {
    this.buf = this.buf.length ? Buffer.concat([this.buf, chunk]) : chunk
    while (this.buf.length >= 12) {
      if (this.buf[0] & 0x80) {
        this.onSession(this.buf.readUInt32BE(4), this.buf.readUInt32BE(8))
        this.buf = this.buf.subarray(12)
        continue
      }
      const size = this.buf.readUInt32BE(8)
      if (this.buf.length < 12 + size) return
      const ptsFlags = this.buf.readBigUInt64BE(0)
      const config = (ptsFlags >> 62n) & 1n
      const key = (ptsFlags >> 61n) & 1n
      const pts = ptsFlags & 0x1fffffffffffffffn
      const data = this.buf.subarray(12, 12 + size)
      this.buf = this.buf.subarray(12 + size)
      this.onPacket(config ? KIND_CONFIG : key ? KIND_KEYFRAME : KIND_FRAME, config ? 0n : pts, Buffer.from(data))
    }
  }
}

/** The binary WebSocket message for one packet. */
export function framePacket(kind: number, ptsMicros: bigint, data: Buffer): Buffer {
  const head = Buffer.alloc(9)
  head.writeUInt8(kind, 0)
  head.writeBigUInt64BE(ptsMicros, 1)
  return Buffer.concat([head, data])
}

// ── A session ───────────────────────────────────────────────────────────────────────────────────

function adb(serial: string, args: string[], timeoutMs = 20_000): Promise<string> {
  const bin = findAdb()
  if (!bin) return Promise.reject(new Error("adb not found"))
  return new Promise((resolve, reject) => execFile(bin, ["-s", serial, ...args], { timeout: timeoutMs }, (err, so, se) => {
    if (err) reject(new Error(String(se || so || err.message).trim())); else resolve(String(so).trim())
  }))
}

function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const s = createServer()
    s.once("error", reject)
    s.listen(0, "127.0.0.1", () => { const p = (s.address() as any).port; s.close(() => resolve(p)) })
  })
}

/** Reads exactly n bytes from the start of a socket's stream; the rest is handed back. */
function readExactly(sock: Socket, n: number, timeoutMs: number): Promise<{ head: Buffer; rest: Buffer }> {
  return new Promise((resolve, reject) => {
    let buf = Buffer.alloc(0)
    const timer = setTimeout(() => { cleanup(); reject(new Error("timeout")) }, timeoutMs)
    const onData = (c: Buffer) => {
      buf = Buffer.concat([buf, c])
      if (buf.length >= n) { cleanup(); resolve({ head: buf.subarray(0, n), rest: buf.subarray(n) }) }
    }
    const onEnd = () => { cleanup(); reject(new Error("closed")) }
    const cleanup = () => { clearTimeout(timer); sock.off("data", onData); sock.off("end", onEnd); sock.off("close", onEnd); sock.off("error", onEnd) }
    sock.on("data", onData)
    sock.once("end", onEnd)
    sock.once("close", onEnd)
    sock.once("error", onEnd)
  })
}

export interface ScreenOptions { maxSize: number; bitRate: number; maxFps: number }

export class ScreenSession {
  width = 0
  height = 0
  deviceName = ""
  private proc: ChildProcess | null = null
  private video: Socket | null = null
  private control: Socket | null = null
  private port = 0
  private closed = false
  private serverLog: string[] = []

  constructor(
    readonly serial: string,
    private onPacket: (packet: Buffer) => boolean,
    private onEnd: (reason: string) => void,
  ) {}

  async start(opts: ScreenOptions) {
    const server = findScrcpyServer()
    if (!server) throw new Error("scrcpy isn't installed on the Mac (brew install scrcpy)")
    const version = await scrcpyVersion()
    const scid = (randomBytes(4).readUInt32BE(0) & 0x7fffffff).toString(16).padStart(8, "0")
    // stop() can land while we're awaiting (phone went away, a new start replaced us). Check after
    // every step so a stopped session never goes on to spawn a server nobody owns.
    const alive = () => { if (this.closed) throw new Error("Screen stopped") }
    await adb(this.serial, ["push", server, "/data/local/tmp/scrcpy-server.jar"], 60_000)
    alive()
    const port = await freePort()
    alive()
    await adb(this.serial, ["forward", `tcp:${port}`, `localabstract:scrcpy_${scid}`])
    this.port = port
    if (this.closed) { adb(this.serial, ["forward", "--remove", `tcp:${port}`]).catch(() => {}); alive() }
    const args = [
      "-s", this.serial, "shell",
      "CLASSPATH=/data/local/tmp/scrcpy-server.jar", "app_process", "/", "com.genymobile.scrcpy.Server", version,
      `scid=${scid}`, "log_level=info", "audio=false", "control=true", "tunnel_forward=true",
      "video_codec=h264", `max_size=${opts.maxSize}`, `max_fps=${opts.maxFps}`, `video_bit_rate=${opts.bitRate}`,
      "send_dummy_byte=true", "stay_awake=false", "power_off_on_close=false", "cleanup=true",
    ]
    this.proc = spawn(findAdb()!, args, { stdio: ["ignore", "pipe", "pipe"] })
    console.log(`  screen ${this.serial}: started (pid ${this.proc.pid})`)
    const log = (c: Buffer) => { for (const l of String(c).split("\n")) if (l.trim()) { this.serverLog.push(l.trim()); if (this.serverLog.length > 20) this.serverLog.shift() } }
    this.proc.stdout!.on("data", log)
    this.proc.stderr!.on("data", log)
    this.proc.on("exit", () => this.end(this.serverLog.find((l) => /ERROR|Exception/.test(l)) ?? "Screen stream ended"))

    // With adb forward, connect() succeeds even before the server listens; the dummy byte tells us
    // the real socket is up. Retry for ~8 s while the server starts.
    let video: Socket | null = null
    let rest: Buffer = Buffer.alloc(0)
    for (let i = 0; i < 40 && !this.closed; i++) {
      const s = await new Promise<Socket | null>((r) => { const c = connect(this.port, "127.0.0.1", () => r(c)); c.once("error", () => r(null)) })
      if (s) {
        try { rest = (await readExactly(s, 1, 1500)).rest; video = s; break } catch { s.destroy() }
      }
      await new Promise((r) => setTimeout(r, 200))
    }
    if (!video) { const why = this.serverLog.slice(-3).join(" · "); this.stop(); throw new Error(`Screen stream didn't start${why ? `: ${why}` : ""}`) }
    if (this.closed) { video.destroy(); alive() }
    this.video = video
    video.pause()
    // The server sends its header only once every socket (video, then control) is connected.
    this.control = await new Promise<Socket>((resolve, reject) => { const c = connect(this.port, "127.0.0.1", () => resolve(c)); c.once("error", reject) })
    this.control.on("data", () => { /* device messages (clipboard) — not used */ })
    this.control.on("error", () => {})
    alive()
    video.resume()
    // Device name (64 bytes, NUL-padded), codec id (4), then the first session packet (12: flags,
    // width, height).
    const meta = await readExactly(video, 64 + 4 + 12 - rest.length, 5000).catch(() => null)
    if (!meta) { this.stop(); throw new Error("Screen stream sent no header") }
    const header = Buffer.concat([rest, meta.head])
    this.deviceName = header.subarray(0, 64).toString("utf8").replace(/\0+$/, "")
    if (!(header[68] & 0x80)) { this.stop(); throw new Error("Unexpected screen stream header (scrcpy version?)") }
    this.width = header.readUInt32BE(72)
    this.height = header.readUInt32BE(76)
    video.pause()

    const parser = new PacketParser(
      (kind, pts, data) => {
      // Back-pressure: if the phone can't keep up (4G), stop reading until the socket drains rather
      // than dropping frames (a dropped H.264 frame smears until the next key frame).
      if (!this.onPacket(framePacket(kind, pts, data))) {
        video.pause()
        setTimeout(() => !this.closed && video.resume(), 100)
      }
      },
      // Rotation: a new session with the new size (followed by a fresh config + key frame).
      (w, h) => { this.width = w; this.height = h },
    )
    if (meta.rest.length) parser.push(meta.rest)
    video.on("data", (c: Buffer) => parser.push(c))
    video.on("close", () => this.end("Device disconnected"))
    video.on("error", () => {})
    video.resume()
    return { width: this.width, height: this.height, deviceName: this.deviceName }
  }

  touch(action: number, pointer: number, x: number, y: number, w: number, h: number) {
    if (!this.control || !this.width) return
    // The app sends coordinates in its own frame size (w×h); scale to the video.
    const vx = w > 0 ? (x * this.width) / w : x
    const vy = h > 0 ? (y * this.height) / h : y
    this.control.write(touchMessage(action, pointer, vx, vy, this.width, this.height))
  }

  key(name: string) {
    const code = KEYS[name]
    if (code == null || !this.control) return
    this.control.write(keyMessage(0, code))
    this.control.write(keyMessage(1, code))
  }

  /** The video size changes with rotation; the app tells us what it last decoded. */
  resize(width: number, height: number) {
    if (width > 0 && height > 0) { this.width = width; this.height = height }
  }

  private end(reason: string) {
    if (this.closed) return
    this.stop()
    this.onEnd(reason)
  }

  stop() {
    if (this.closed) return
    this.closed = true
    console.log(`  screen ${this.serial}: stopped (pid ${this.proc?.pid ?? "-"})`)
    this.video?.destroy()
    this.control?.destroy()
    this.proc?.kill("SIGTERM")
    if (this.port) adb(this.serial, ["forward", "--remove", `tcp:${this.port}`]).catch(() => {})
  }
}
