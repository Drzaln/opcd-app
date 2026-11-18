// End-to-end: spawns the built helper on 127.0.0.1 with a throwaway HOME and auto-approve (loopback only).
import { test, before, after } from "node:test"
import assert from "node:assert/strict"
import { spawn } from "node:child_process"
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync, chmodSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import WebSocket from "ws"

const port = 40000 + Math.floor(Math.random() * 20000)
const base = `http://127.0.0.1:${port}`
const home = mkdtempSync(join(tmpdir(), "oc-helper-home-"))
const project = mkdtempSync(join(tmpdir(), "oc-helper-proj-"))
let helper
const fakeAdb = join(home, "fake-adb")

// Stand-in adb: canned `devices -l`, connect/pair/reverse answers, and a short logcat.
const FAKE_ADB = `#!/usr/bin/env node
const a = process.argv.slice(2)
const s = a[0] === "-s" ? a.splice(0, 2)[1] : null
const cmd = a[0]
if (cmd === "devices") {
  console.log("List of devices attached")
  console.log("emulator-5554          device product:sdk_gphone64 model:sdk_gphone64_arm64 device:emu64a transport_id:1")
  console.log("10DE5B08CN0006A        device usb:34603008X product:I2219T model:I2219 device:I2219 transport_id:2")
  console.log("100.64.1.2:5555        offline transport_id:3")
} else if (cmd === "connect") console.log(a[1] === "100.64.1.9:5555" ? "failed to connect to '100.64.1.9:5555': Connection refused" : "connected to " + a[1])
else if (cmd === "pair") console.log("Successfully paired to " + a[1] + " [guid=adb-XYZ]")
else if (cmd === "reverse") console.log("8081")
else if (cmd === "logcat" && a.includes("-d")) {
  console.log("--------- beginning of main")
  console.log("11-18 16:00:00.000  1234  1250 I ReactNativeJS: Running \\"main\\" (from the backlog)")
} else if (cmd === "logcat") {
  const lines = [
    "11-18 17:01:02.100  1234  1250 I ReactNativeJS: Running \\"main\\"",
    "11-18 17:01:02.200  1234  1250 W ReactNativeJS: Possible unhandled promise",
    "11-18 17:01:02.300  1234  1251 E AndroidRuntime: FATAL EXCEPTION: main",
  ]
  for (const l of lines) console.log(l)
  setTimeout(() => {}, 60000)
} else { console.error("unknown " + a.join(" ")); process.exit(1) }
`

before(async () => {
  writeFileSync(fakeAdb, FAKE_ADB)
  chmodSync(fakeAdb, 0o755)
  mkdirSync(join(project, "src"), { recursive: true })
  writeFileSync(join(project, "tsconfig.json"), JSON.stringify({ compilerOptions: { strict: true, jsx: "react-jsx", target: "es2022", module: "esnext", moduleResolution: "bundler" } }))
  writeFileSync(join(project, "src", "a.ts"), "export const greeting = 'hi'\nexport function shout(s: string) { return s.toUpperCase() }\n")
  writeFileSync(join(project, "src", "b.ts"), "import { shout } from './a'\nconst n: number = 'oops'\nshout('x').toLower\n")
  helper = spawn(process.execPath, ["dist/helper.mjs", "--port", String(port), "--host", "127.0.0.1"], {
    env: { ...process.env, HOME: home, OC_HELPER_AUTO_APPROVE: "1", OC_HELPER_ADB: fakeAdb },
    stdio: ["ignore", "pipe", "pipe"],
  })
  await new Promise((resolve, reject) => {
    helper.stdout.on("data", (d) => { if (String(d).includes("listening")) resolve() })
    helper.on("exit", (c) => reject(new Error(`helper exited ${c}`)))
  })
})

after(() => helper?.kill())

const post = (path, body) => fetch(base + path, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) })

let token
test("info + pairing", async () => {
  const info = await (await fetch(base + "/helper/info")).json()
  assert.equal(info.name, "opencode-mobile-helper")
  assert.ok(info.features.includes("lsp"))
  assert.ok(info.features.includes("metro"))
  assert.ok(info.features.includes("adb"), "adb advertised when platform-tools are found")
  const start = await (await post("/helper/pair/start", { device: "Test phone" })).json()
  assert.match(start.code, /^\d{4}$/)
  const res = await post("/helper/pair/wait", { requestId: start.requestId })
  assert.equal(res.status, 200)
  token = (await res.json()).token
  assert.ok(token.length > 30)
  const devices = JSON.parse(readFileSync(join(home, ".config", "opencode-mobile-helper", "devices.json"), "utf8"))
  assert.equal(devices.length, 1)
  assert.ok(!JSON.stringify(devices).includes(token), "token stored only as a hash")
})

function connect(auth) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/helper/ws`, { headers: auth ? { authorization: `Bearer ${auth}` } : {} })
    ws.on("open", () => resolve(ws))
    ws.on("unexpected-response", (_req, res) => reject(new Error(`HTTP ${res.statusCode}`)))
    ws.on("error", reject)
  })
}

test("websocket refuses missing or wrong tokens", async () => {
  await assert.rejects(connect(null), /401/)
  await assert.rejects(connect("x".repeat(43)), /401/)
})

test("lsp: completions + diagnostics through the relay, and format", async () => {
  const ws = await connect(token)
  let nextId = 1
  const waiting = new Map()
  const lspWaiting = new Map()
  const diagnostics = []
  let session
  ws.on("message", (raw) => {
    const msg = JSON.parse(String(raw))
    if (msg.id != null && waiting.has(msg.id)) { waiting.get(msg.id)(msg); waiting.delete(msg.id); return }
    if (msg.method === "lsp.message") {
      const lsp = JSON.parse(msg.params.message)
      if (lsp.id != null && lspWaiting.has(lsp.id)) { lspWaiting.get(lsp.id)(lsp); lspWaiting.delete(lsp.id) }
      else if (lsp.method === "textDocument/publishDiagnostics") diagnostics.push(lsp.params)
      else if (lsp.id != null && lsp.method) ws.send(JSON.stringify({ method: "lsp.send", params: { session, message: JSON.stringify({ jsonrpc: "2.0", id: lsp.id, result: null }) } }))
    }
  })
  const rpc = (method, params) => new Promise((resolve) => { const id = nextId++; waiting.set(id, resolve); ws.send(JSON.stringify({ id, method, params })) })
  let lspId = 1
  const lsp = (method, params) => new Promise((resolve) => {
    const id = lspId++
    lspWaiting.set(id, resolve)
    ws.send(JSON.stringify({ method: "lsp.send", params: { session, message: JSON.stringify({ jsonrpc: "2.0", id, method, params }) } }))
  })
  const notifyLsp = (method, params) => ws.send(JSON.stringify({ method: "lsp.send", params: { session, message: JSON.stringify({ jsonrpc: "2.0", method, params }) } }))

  assert.match((await rpc("lsp.start", { root: "/" })).error.message, /absolute folder/)
  session = (await rpc("lsp.start", { root: project })).result.session
  const init = await lsp("initialize", { processId: null, rootUri: `file://${project}`, capabilities: { textDocument: { publishDiagnostics: {} } } })
  assert.ok(init.result.capabilities.completionProvider, "server advertises completions")
  notifyLsp("initialized", {})
  const uri = `file://${project}/src/b.ts`
  notifyLsp("textDocument/didOpen", { textDocument: { uri, languageId: "typescript", version: 1, text: readFileSync(join(project, "src", "b.ts"), "utf8") } })
  let items = []
  for (let i = 0; i < 20 && !items.some((x) => x.label === "toLowerCase"); i++) {
    const completion = await lsp("textDocument/completion", { textDocument: { uri }, position: { line: 2, character: 18 } })
    items = completion.result?.items ?? completion.result ?? []
    if (!items.length) await new Promise((r) => setTimeout(r, 300))
  }
  assert.ok(items.some((i) => i.label === "toLowerCase"), `string methods offered: ${items.slice(0, 5).map((i) => i.label)}`)
  for (let i = 0; i < 50 && !diagnostics.some((d) => d.uri === uri && d.diagnostics.length); i++) await new Promise((r) => setTimeout(r, 200))
  const messages = diagnostics.filter((d) => d.uri === uri).flatMap((d) => d.diagnostics.map((x) => x.message))
  assert.ok(messages.some((m) => /not assignable to type 'number'/.test(m)), `type error reported: ${messages}`)
  const def = await lsp("textDocument/definition", { textDocument: { uri }, position: { line: 0, character: 10 } })
  const target = Array.isArray(def.result) ? def.result[0] : def.result
  assert.match(target.uri ?? target.targetUri, /src\/a\.ts$/)

  const formatted = (await rpc("format", { root: project, path: "src/x.ts", text: "const  a = {b:1}" })).result
  assert.equal(formatted.text, "const a = { b: 1 };\n")
  assert.deepEqual(formatted.steps, ["prettier"])
  assert.match((await rpc("format", { root: project, path: "../evil.ts", text: "x" })).error.message, /outside/)
  ws.close()
})

// A stand-in for `react-native start`: answers /status and /reload like Metro, repaints a progress
// line with \r, and reports a resolve error on stderr.
const FAKE_METRO = `#!/usr/bin/env node
const http = require("http")
const { WebSocketServer } = require(${JSON.stringify(join(process.cwd(), "node_modules", "ws"))})
const port = Number(process.argv[process.argv.indexOf("--port") + 1])
const server = http.createServer((req, res) => {
  if (req.url === "/status") return res.end("packager-status:running")
  res.statusCode = 404; res.end()
})
// Metro's message socket: the reload broadcast arrives here.
const wss = new WebSocketServer({ server, path: "/message" })
wss.on("connection", (ws) => ws.on("message", (m) => { if (JSON.parse(String(m)).method === "reload") console.log("reload received") }))
server.listen(port, "127.0.0.1", () => {
  console.log("\\u001b[1mWelcome to Metro\\u001b[22m on " + port + " in " + process.cwd())
  process.stdout.write("BUNDLE  ./index.js 10.0%\\rBUNDLE  ./index.js 55.0%\\rBUNDLE  ./index.js 100.0%\\n")
  console.error("error: Unable to resolve module ./Missing from App.tsx")
})
process.on("SIGTERM", () => { console.log("bye"); process.exit(0) })
`

test("metro: detect, start, logs, reload, stop", async () => {
  const rn = mkdtempSync(join(tmpdir(), "oc-helper-rn-"))
  writeFileSync(join(rn, "package.json"), JSON.stringify({ name: "app", scripts: { start: "react-native start" }, dependencies: { "react-native": "0.76.0", react: "18.3.1" } }))
  mkdirSync(join(rn, "node_modules", ".bin"), { recursive: true })
  writeFileSync(join(rn, "node_modules", ".bin", "react-native"), FAKE_METRO)
  chmodSync(join(rn, "node_modules", ".bin", "react-native"), 0o755)
  const expo = mkdtempSync(join(tmpdir(), "oc-helper-expo-"))
  writeFileSync(join(expo, "package.json"), JSON.stringify({ dependencies: { expo: "~52.0.0", "react-native": "0.76.0" } }))

  const ws = await connect(token)
  let nextId = 1
  const waiting = new Map()
  const logs = []
  const states = []
  ws.on("message", (raw) => {
    const msg = JSON.parse(String(raw))
    if (msg.id != null && waiting.has(msg.id)) { waiting.get(msg.id)(msg); waiting.delete(msg.id) }
    else if (msg.method === "metro.log" && msg.params.root === rn) {
      if (msg.params.replace && logs.length && logs[logs.length - 1].seq === msg.params.line.seq) logs[logs.length - 1] = msg.params.line
      else logs.push(msg.params.line)
    } else if (msg.method === "metro.status" && msg.params.root === rn) states.push(msg.params.state)
  })
  const rpc = (method, params) => new Promise((resolve) => { const id = nextId++; waiting.set(id, resolve); ws.send(JSON.stringify({ id, method, params })) })
  const until = async (pred, what) => { for (let i = 0; i < 80 && !pred(); i++) await new Promise((r) => setTimeout(r, 100)); assert.ok(pred(), what) }

  assert.equal((await rpc("metro.status", { root: expo })).result.detected, "npx expo start --port 8081")
  assert.match((await rpc("metro.status", { root: project })).result.detectError, /No package.json/)
  const web = mkdtempSync(join(tmpdir(), "oc-helper-web-"))
  writeFileSync(join(web, "package.json"), JSON.stringify({ dependencies: { react: "18.3.1" } }))
  assert.match((await rpc("metro.start", { root: web })).error.message, /Not a React Native/)
  assert.match((await rpc("metro.start", { root: rn, port: 80 })).error.message, /port/)

  const metroPort = 30000 + Math.floor(Math.random() * 9000)
  const started = (await rpc("metro.start", { root: rn, port: metroPort })).result
  assert.equal(started.state, "starting")
  assert.equal(started.command, `npx react-native start --port ${metroPort}`)
  await until(() => states.includes("running"), `state reaches running: ${states}`)
  await until(() => logs.some((l) => l.level === "error"), "error line arrives")
  const texts = logs.map((l) => l.text)
  assert.ok(texts.some((t) => t === `Welcome to Metro on ${metroPort} in ${rn}` || t.startsWith("Welcome to Metro")), `ANSI stripped: ${texts}`)
  assert.equal(texts.filter((t) => t.startsWith("BUNDLE")).length, 1, `progress collapsed into one line: ${texts}`)
  assert.ok(texts.includes("BUNDLE  ./index.js 100.0%"))
  assert.match(logs.find((l) => l.level === "error").text, /Unable to resolve module/)

  // A second phone (or this one after a 4G drop) catches up from the buffer.
  const status = (await rpc("metro.status", { root: rn })).result
  assert.equal(status.state, "running")
  assert.equal(status.errors, 1)
  assert.ok(status.lines.some((l) => /Unable to resolve/.test(l.text)))

  assert.equal((await rpc("metro.reload", { root: rn })).result.via, "message")
  await until(() => logs.some((l) => l.text === "reload received"), "app reload reached Metro")

  const stopped = (await rpc("metro.stop", { root: rn })).result
  assert.equal(stopped.state, "stopped")
  await until(() => logs.some((l) => l.text === "bye"), "Metro got SIGTERM")
  assert.match((await rpc("metro.reload", { root: rn })).error.message, /isn't running/)
  ws.close()
})

test("adb: devices, connect, pair, build & run, logcat", async () => {
  const ws = await connect(token)
  let nextId = 1
  const waiting = new Map()
  const runLogs = []
  const runStates = []
  const logcat = []
  const app = mkdtempSync(join(tmpdir(), "oc-helper-gradle-"))
  writeFileSync(join(app, "gradlew"), "#!/bin/sh\necho \"> Task :app:installDebug\"\necho \"Installing APK on $ANDROID_SERIAL\" \necho \"w: some kotlin warning\" 1>&2\necho BUILD SUCCESSFUL\n")
  chmodSync(join(app, "gradlew"), 0o755)
  ws.on("message", (raw) => {
    const m = JSON.parse(String(raw))
    if (m.id != null && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id) }
    else if (m.method === "run.log" && m.params.root === app) runLogs.push(m.params.line)
    else if (m.method === "run.status" && m.params.root === app) runStates.push(m.params.state)
    else if (m.method === "logcat.lines") logcat.push(...m.params.lines)
  })
  const rpc = (method, params) => new Promise((resolve) => { const id = nextId++; waiting.set(id, resolve); ws.send(JSON.stringify({ id, method, params })) })
  const until = async (pred, what) => { for (let i = 0; i < 60 && !pred(); i++) await new Promise((r) => setTimeout(r, 100)); assert.ok(pred(), what) }

  const { devices } = (await rpc("adb.devices", {})).result
  assert.deepEqual(devices.map((d) => [d.serial, d.transport, d.state]), [
    ["emulator-5554", "emulator", "device"], ["10DE5B08CN0006A", "usb", "device"], ["100.64.1.2:5555", "wifi", "offline"],
  ])
  assert.equal(devices[1].model, "I2219")
  assert.match((await rpc("adb.connect", { host: "100.64.1.2", port: 5555 })).result.output, /connected to/)
  assert.match((await rpc("adb.connect", { host: "100.64.1.9", port: 5555 })).error.message, /refused/)
  assert.match((await rpc("adb.connect", { host: "a;rm -rf /", port: 5555 })).error.message, /host/)
  assert.match((await rpc("adb.pair", { host: "100.64.1.2", port: 37001, code: "123" })).error.message, /6 digits/)
  assert.match((await rpc("adb.pair", { host: "100.64.1.2", port: 37001, code: "123456" })).result.output, /Successfully paired/)

  assert.equal((await rpc("run.status", { root: app })).result.detected, "./gradlew installDebug")
  assert.match((await rpc("run.status", { root: project })).result.detectError, /Nothing to build/)
  const started = (await rpc("run.start", { root: app, serial: "emulator-5554" })).result
  assert.equal(started.state, "running")
  await until(() => runStates.includes("succeeded"), `build finishes: ${runStates}`)
  const texts = runLogs.map((l) => l.text)
  assert.ok(texts.includes("Installing APK on emulator-5554"), `ANDROID_SERIAL passed: ${texts}`)
  assert.equal(runLogs.find((l) => l.text.startsWith("w:")).level, "warn")
  assert.equal(runLogs.filter((l) => l.level === "error").length, 0, "a successful build has no red lines")
  assert.match((await rpc("run.start", { root: app, serial: "bad serial!" })).error.message, /device/)

  const { id } = (await rpc("logcat.start", { serial: "emulator-5554", mode: "app" })).result
  await until(() => logcat.length >= 4, "backlog + live lines arrive")
  assert.ok(logcat[0].text.includes("from the backlog"), "filtered backlog comes first")
  assert.deepEqual(logcat.slice(1).map((l) => l.level), ["info", "warn", "error"])
  assert.match((await rpc("logcat.start", { serial: "emulator-5554", mode: "nope" })).error.message, /mode/)
  assert.ok((await rpc("logcat.stop", { id })).result.ok)
  ws.close()
})
