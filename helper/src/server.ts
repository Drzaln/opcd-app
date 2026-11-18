// HTTP (discovery + pairing) and an authenticated WebSocket carrying JSON-RPC-style messages.
//
//   GET  /helper/info                       → { name, version, hostname, protocol, features }
//   POST /helper/pair/start {device}        → { requestId, code }   (Mac shows Allow/Deny with the code)
//   POST /helper/pair/wait  {requestId}     → { token, deviceId } | 403 denied | 408 timeout
//   WS   /helper/ws  (Authorization: Bearer <token>)
//        client → { id?, method, params }   server → { id, result } | { id, error: { message } }
//        server notifications → { method, params }
import { createServer, type IncomingMessage, type ServerResponse, type Server } from "node:http"
import { hostname } from "node:os"
import { randomBytes } from "node:crypto"
import { statSync } from "node:fs"
import { isAbsolute, resolve } from "node:path"
import { WebSocketServer, type WebSocket } from "ws"
import { authenticate, issueToken, loadDevices, pairingCode, saveDevices } from "./auth.ts"
import { confirmPairing, notify, type Approval } from "./confirm.ts"
import { LspSession } from "./lsp.ts"
import { formatText, insideRoot } from "./format.ts"
import { MetroManager } from "./metro.ts"
import { ScreenSession, findScrcpyServer } from "./screen.ts"
import { EmulatorManager, listAvds, findEmulator } from "./emu.ts"
import { SimulatorManager, SimLogStream, listSims, findXcrun, isSimulator, type SimLogMode } from "./sim.ts"
import { RunManager, LogcatStream, listDevices, pair, connect, disconnect, reverse, checkSerial, findAdb, type LogcatMode } from "./adb.ts"

export const VERSION = "0.1.0"
export const PROTOCOL = 1
export const FEATURES = ["pair", "lsp", "format", "metro"]

/** Features this Mac can offer right now ("adb" only when platform-tools are installed, "ios" with Xcode). */
export function features(): string[] {
  const ios = findXcrun() ? ["ios"] : []
  if (!findAdb()) return [...FEATURES, ...ios]
  return [...FEATURES, "adb", ...(findScrcpyServer() ? ["screen"] : []), ...(findEmulator() ? ["emulator"] : []), ...ios]
}

/** One Metro registry for the whole helper (shared by every interface it listens on). */
export const metro = new MetroManager()
/** Build & run per project root, also helper-owned. */
export const runs = new RunManager()
/** Live screens by device serial, across connections: a new start replaces any older stream. */
const screensBySerial = new Map<string, ScreenSession>()

/** Emulator starts (avdslim or the SDK emulator), helper-owned. */
export const emulators = new EmulatorManager()
/** iOS simulator starts (simslim or simctl), helper-owned. */
export const simulators = new SimulatorManager()

interface PendingPair {
  code: string
  device: string
  decision: Promise<Approval>
  createdAt: number
}

export interface ServerOptions {
  autoApprove: boolean
  devicesFile?: string
}

export function createHelperServer(options: ServerOptions): Server {
  const pending = new Map<string, PendingPair>()

  const http = createServer(async (req, res) => {
    try {
      await route(req, res)
    } catch (e: any) {
      json(res, 500, { error: String(e?.message ?? e) })
    }
  })

  async function route(req: IncomingMessage, res: ServerResponse) {
    const url = new URL(req.url ?? "/", "http://helper")
    if (req.method === "GET" && url.pathname === "/helper/info") {
      return json(res, 200, { name: "opencode-mobile-helper", version: VERSION, hostname: hostname(), protocol: PROTOCOL, features: features() })
    }
    if (req.method === "POST" && url.pathname === "/helper/pair/start") {
      const body = await readJson(req)
      const device = String(body?.device ?? "Android device").slice(0, 60)
      // One request at a time: a stranger on the network can't queue up dialogs.
      for (const [id, p] of pending) if (Date.now() - p.createdAt > 70_000) pending.delete(id)
      if (pending.size > 0) return json(res, 429, { error: "Another pairing request is waiting on the Mac" })
      const requestId = randomBytes(12).toString("base64url")
      const code = pairingCode()
      const decision = confirmPairing(device, code, options.autoApprove)
      pending.set(requestId, { code, device, decision, createdAt: Date.now() })
      return json(res, 200, { requestId, code })
    }
    if (req.method === "POST" && url.pathname === "/helper/pair/wait") {
      const body = await readJson(req)
      const p = pending.get(String(body?.requestId ?? ""))
      if (!p) return json(res, 404, { error: "Unknown or expired pairing request" })
      const decision = await p.decision
      pending.delete(String(body.requestId))
      if (decision !== "allow") return json(res, decision === "timeout" ? 408 : 403, { error: decision === "timeout" ? "Nobody answered on the Mac" : "Denied on the Mac" })
      const { device, token } = issueToken(p.device, options.devicesFile)
      notify("OpenCode Mobile", `Paired with ${device.name}`)
      console.log(`  Paired with "${device.name}" (${device.id})`)
      return json(res, 200, { token, deviceId: device.id })
    }
    json(res, 404, { error: "not found" })
  }

  const wss = new WebSocketServer({ noServer: true, maxPayload: 16 * 1024 * 1024 })
  http.on("upgrade", (req, socket, head) => {
    const url = new URL(req.url ?? "/", "http://helper")
    const token = /^Bearer (.+)$/.exec(req.headers.authorization ?? "")?.[1]
    const devices = loadDevices(options.devicesFile)
    const device = url.pathname === "/helper/ws" ? authenticate(token, devices) : null
    if (!device) {
      socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n")
      socket.destroy()
      return
    }
    device.lastSeen = new Date().toISOString()
    saveDevices(devices, options.devicesFile)
    wss.handleUpgrade(req, socket, head, (ws) => onConnection(ws, device.name))
  })

  return http
}

function onConnection(ws: WebSocket, deviceName: string) {
  console.log(`  ${deviceName} connected`)
  // Heartbeat: a phone that vanished (4G drop, Tailscale half-open socket) is dropped within ~40 s,
  // so its screen stream / logcat don't keep running. OkHttp answers pings by itself.
  let alive = true
  ws.on("pong", () => { alive = true })
  const heartbeat = setInterval(() => {
    if (!alive) { ws.terminate(); return }
    alive = false
    try { ws.ping() } catch { /* closing */ }
  }, 20_000)
  const sessions = new Map<string, LspSession>()
  const notifyClient = (method: string, params: unknown) => {
    if (ws.readyState === ws.OPEN) ws.send(JSON.stringify({ method, params }))
  }
  // Metro keeps running across reconnects; every connected phone sees its logs and state.
  const unsubscribeMetro = metro.subscribe((event, root, payload) =>
    notifyClient(event === "log" ? "metro.log" : "metro.status", { root, ...(payload as object) }))
  const unsubscribeRuns = runs.subscribe((event, root, payload) =>
    notifyClient(event === "log" ? "run.log" : "run.status", { root, ...(payload as object) }))
  const unsubscribeEmu = emulators.subscribe((event, payload) => notifyClient(event === "log" ? "emu.log" : "emu.status", payload))
  const unsubscribeSim = simulators.subscribe((event, payload) => notifyClient(event === "log" ? "sim.log" : "sim.status", payload))
  // Logcat streams are views: they end with this connection.
  const logcats = new Map<string, LogcatStream | SimLogStream>()
  // One live screen per connection (binary frames on this socket).
  let screen: ScreenSession | null = null

  ws.on("message", async (raw) => {
    let msg: any
    try { msg = JSON.parse(String(raw)) } catch { return }
    const reply = (result: unknown) => msg.id != null && ws.send(JSON.stringify({ id: msg.id, result }))
    const fail = (message: string) => msg.id != null && ws.send(JSON.stringify({ id: msg.id, error: { message } }))
    try {
      switch (msg.method) {
        case "ping":
          return reply({ pong: Date.now() })
        case "lsp.start": {
          const root = checkRoot(msg.params?.root)
          const id = randomBytes(6).toString("hex")
          const session = new LspSession(
            id,
            root,
            (message) => notifyClient("lsp.message", { session: id, message }),
            (code) => { sessions.delete(id); notifyClient("lsp.exit", { session: id, code }) },
          )
          sessions.set(id, session)
          return reply({ session: id })
        }
        case "lsp.send":
          sessions.get(String(msg.params?.session))?.send(String(msg.params?.message ?? ""))
          return reply({ ok: true })
        case "lsp.stop":
          sessions.get(String(msg.params?.session))?.dispose()
          sessions.delete(String(msg.params?.session))
          return reply({ ok: true })
        case "format": {
          const root = checkRoot(msg.params?.root)
          const file = resolve(root, String(msg.params?.path ?? ""))
          if (!insideRoot(root, file)) return fail("Path is outside the project")
          return reply(await formatText(root, file, String(msg.params?.text ?? "")))
        }
        case "metro.status":
          return reply(metro.status(checkRoot(msg.params?.root), Number(msg.params?.tail ?? 500)))
        case "metro.start":
          return reply(await metro.start(checkRoot(msg.params?.root), checkPort(msg.params?.port)))
        case "metro.stop":
          return reply(await metro.stop(checkRoot(msg.params?.root)))
        case "metro.reload":
          return reply(await metro.reload(checkRoot(msg.params?.root)))
        case "adb.devices":
          return reply(await listDevices())
        case "adb.pair":
          return reply(await pair(msg.params?.host, msg.params?.port, msg.params?.code))
        case "adb.connect":
          return reply(await connect(msg.params?.host, msg.params?.port))
        case "adb.disconnect":
          return reply(await disconnect(msg.params?.serial))
        case "adb.reverse":
          return reply(await reverse(msg.params?.serial, checkPort(msg.params?.port)))
        case "run.status":
          return reply(runs.status(checkRoot(msg.params?.root), Number(msg.params?.tail ?? 500)))
        case "run.start":
          return reply(await runs.start(checkRoot(msg.params?.root), checkSerial(msg.params?.serial)))
        case "run.stop":
          return reply(await runs.stop(checkRoot(msg.params?.root)))
        case "logcat.start": {
          const mode = String(msg.params?.mode ?? "app") as LogcatMode
          if (!["app", "errors", "all"].includes(mode)) return fail("Unknown logcat mode")
          const id = randomBytes(6).toString("hex")
          const serial = checkSerial(msg.params?.serial)
          const send = (lines: unknown[], skipped: number) => notifyClient("logcat.lines", { id, lines, skipped })
          const exit = (code: number | null) => { logcats.delete(id); notifyClient("logcat.exit", { id, code }) }
          // A simulator UDID: the iOS log (same notifications, so the app's Logs tab works for both).
          const stream = isSimulator(serial) ? new SimLogStream(id, serial, mode as SimLogMode, send, exit) : new LogcatStream(id, serial, mode, send, exit)
          logcats.set(id, stream)
          return reply({ id })
        }
        case "emu.list":
          return reply({ ...(await listAvds()), starting: emulators.statuses() })
        case "emu.start": {
          const p = msg.params ?? {}
          return reply(await emulators.start(String(p.avd ?? ""), { headless: p.headless !== false, cold: p.cold === true, ram: Number(p.ram ?? 1536), slim: p.slim !== false }))
        }
        case "emu.stop":
          return reply(await emulators.stop(String(msg.params?.serial ?? "")))
        case "sim.list":
          return reply({ ...(await listSims()), starting: simulators.statuses() })
        case "sim.start": {
          const p = msg.params ?? {}
          return reply(await simulators.start(String(p.udid ?? ""), { slim: p.slim !== false, window: p.window === true }))
        }
        case "sim.stop":
          return reply(await simulators.stop(String(msg.params?.udid ?? "")))
        case "screen.start": {
          screen?.stop()
          const serial = checkSerial(msg.params?.serial)
          if (isSimulator(serial)) return fail("The live screen isn't available for iOS simulators yet")
          // An older stream of this device (another phone, or a connection that died half-open).
          screensBySerial.get(serial)?.stop()
          const session = new ScreenSession(
            serial,
            (packet) => {
              if (ws.readyState !== ws.OPEN) return false
              ws.send(packet, { binary: true })
              return ws.bufferedAmount < 2 * 1024 * 1024
            },
            (reason) => { if (screen === session) screen = null; notifyClient("screen.end", { reason }) },
          )
          screen = session
          screensBySerial.set(serial, session)
          const clamp = (v: unknown, lo: number, hi: number, d: number) => { const n = Number(v); return Number.isFinite(n) ? Math.min(hi, Math.max(lo, Math.round(n))) : d }
          try {
            return reply(await session.start({
              maxSize: clamp(msg.params?.maxSize, 320, 1920, 800),
              bitRate: clamp(msg.params?.bitRate, 250_000, 8_000_000, 1_500_000),
              maxFps: clamp(msg.params?.maxFps, 5, 60, 30),
            }))
          } catch (e) {
            if (screen === session) screen = null
            throw e
          }
        }
        case "screen.input": {
          const p = msg.params ?? {}
          if (typeof p.key === "string") screen?.key(p.key)
          else screen?.touch(Number(p.action) | 0, Number(p.pointer) | 0, Number(p.x), Number(p.y), Number(p.w), Number(p.h))
          return reply({ ok: true })
        }
        case "screen.resize":
          screen?.resize(Number(msg.params?.width), Number(msg.params?.height))
          return reply({ ok: true })
        case "screen.stop":
          screen?.stop()
          screen = null
          return reply({ ok: true })
        case "logcat.stop":
          logcats.get(String(msg.params?.id))?.stop()
          logcats.delete(String(msg.params?.id))
          return reply({ ok: true })
        default:
          return fail(`Unknown method ${msg.method}`)
      }
    } catch (e: any) {
      fail(String(e?.message ?? e))
    }
  })

  ws.on("close", () => {
    clearInterval(heartbeat)
    unsubscribeMetro()
    unsubscribeRuns()
    unsubscribeEmu()
    unsubscribeSim()
    screen?.stop()
    screen = null
    for (const l of logcats.values()) l.stop()
    logcats.clear()
    for (const s of sessions.values()) s.dispose()
    sessions.clear()
    console.log(`  ${deviceName} disconnected`)
  })
}

/** A project root must be an existing absolute directory (never `/`). */
export function checkRoot(value: unknown): string {
  const root = String(value ?? "")
  if (!isAbsolute(root) || root.includes("\0") || resolve(root) === "/") throw new Error("Project root must be an absolute folder")
  const st = statSync(root, { throwIfNoEntry: false })
  if (!st?.isDirectory()) throw new Error(`Not a folder: ${root}`)
  return resolve(root)
}

/** Metro port: 8081 unless the phone asks for another unprivileged one. */
export function checkPort(value: unknown): number {
  if (value == null) return 8081
  const port = Number(value)
  if (!Number.isInteger(port) || port < 1024 || port > 65535 || port === 4096 || port === 4097) throw new Error("Pick a port between 1024 and 65535")
  return port
}

function json(res: ServerResponse, status: number, body: unknown) {
  res.writeHead(status, { "content-type": "application/json", "cache-control": "no-store" })
  res.end(JSON.stringify(body))
}

function readJson(req: IncomingMessage): Promise<any> {
  return new Promise((resolveBody) => {
    let size = 0
    const chunks: Buffer[] = []
    req.on("data", (c: Buffer) => { size += c.length; if (size < 64 * 1024) chunks.push(c) })
    req.on("end", () => { try { resolveBody(JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}")) } catch { resolveBody({}) } })
  })
}
