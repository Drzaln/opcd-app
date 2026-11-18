// End-to-end: spawns the built helper on 127.0.0.1 with a throwaway HOME and auto-approve (loopback only).
import { test, before, after } from "node:test"
import assert from "node:assert/strict"
import { spawn } from "node:child_process"
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import WebSocket from "ws"

const port = 40000 + Math.floor(Math.random() * 20000)
const base = `http://127.0.0.1:${port}`
const home = mkdtempSync(join(tmpdir(), "oc-helper-home-"))
const project = mkdtempSync(join(tmpdir(), "oc-helper-proj-"))
let helper

before(async () => {
  mkdirSync(join(project, "src"), { recursive: true })
  writeFileSync(join(project, "tsconfig.json"), JSON.stringify({ compilerOptions: { strict: true, jsx: "react-jsx", target: "es2022", module: "esnext", moduleResolution: "bundler" } }))
  writeFileSync(join(project, "src", "a.ts"), "export const greeting = 'hi'\nexport function shout(s: string) { return s.toUpperCase() }\n")
  writeFileSync(join(project, "src", "b.ts"), "import { shout } from './a'\nconst n: number = 'oops'\nshout('x').toLower\n")
  helper = spawn(process.execPath, ["dist/helper.mjs", "--port", String(port), "--host", "127.0.0.1"], {
    env: { ...process.env, HOME: home, OC_HELPER_AUTO_APPROVE: "1" },
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
