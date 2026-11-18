// opencode-mobile-helper: optional Mac companion for OpenCode Mobile.
//
//   node dist/helper.mjs [--port 4097] [--host <ip>]...   start (default: Tailscale IP + 127.0.0.1)
//   node dist/helper.mjs devices                          list paired devices
//   node dist/helper.mjs revoke <id|name>                 unpair a device
import { execFileSync } from "node:child_process"
import { createHelperServer, metro, VERSION } from "./server.ts"
import { loadDevices, revoke } from "./auth.ts"

const args = process.argv.slice(2)

if (args[0] === "devices") {
  const devices = loadDevices()
  if (devices.length === 0) console.log("No paired devices.")
  for (const d of devices) console.log(`${d.id}  ${d.name}  paired ${d.pairedAt}${d.lastSeen ? `  last seen ${d.lastSeen}` : ""}`)
  process.exit(0)
}
if (args[0] === "revoke") {
  const removed = args[1] ? revoke(args[1]) : 0
  console.log(removed ? `Revoked ${removed} device(s).` : "No matching device.")
  process.exit(removed ? 0 : 1)
}

const flag = (name: string) => {
  const values: string[] = []
  args.forEach((a, i) => { if (a === name && args[i + 1]) values.push(args[i + 1]) })
  return values
}
const port = Number(flag("--port")[0] ?? process.env.OC_HELPER_PORT ?? 4097)

/** The Mac's Tailscale IPv4, so the helper is reachable on the tailnet but not on the café Wi-Fi. */
function tailscaleIp(): string | null {
  for (const bin of ["tailscale", "/Applications/Tailscale.app/Contents/MacOS/Tailscale", "/usr/local/bin/tailscale", "/opt/homebrew/bin/tailscale"]) {
    try {
      const ip = execFileSync(bin, ["ip", "-4"], { encoding: "utf8", timeout: 3000, stdio: ["ignore", "pipe", "ignore"] }).trim().split("\n")[0]
      if (/^\d+\.\d+\.\d+\.\d+$/.test(ip)) return ip
    } catch { /* try the next location */ }
  }
  return null
}

let hosts = flag("--host")
if (hosts.length === 0) {
  const ts = tailscaleIp()
  hosts = ts ? [ts, "127.0.0.1"] : ["127.0.0.1"]
  if (!ts) console.warn("  Tailscale not found — listening on 127.0.0.1 only. Pass --host <ip> to choose an interface.")
}
const loopbackOnly = hosts.every((h) => h === "127.0.0.1" || h === "::1" || h === "localhost")
// Test hook: lets automated tests pair without a person at the Mac. Never honoured on a network interface.
const autoApprove = process.env.OC_HELPER_AUTO_APPROVE === "1" && loopbackOnly
if (process.env.OC_HELPER_AUTO_APPROVE === "1" && !loopbackOnly) console.warn("  OC_HELPER_AUTO_APPROVE ignored: only allowed on 127.0.0.1")

console.log(`OpenCode Mobile helper ${VERSION}`)
for (const host of hosts) {
  const server = createHelperServer({ autoApprove })
  server.on("error", (e: any) => console.error(`  ${host}:${port} — ${e.message}`))
  server.listen(port, host, () => console.log(`  listening on http://${host}:${port}`))
}
console.log("  Pair from the app: Settings → Mac helper → Pair.")

// Take Metro (and its watchers) down with the helper instead of leaving orphans on the port.
for (const signal of ["SIGINT", "SIGTERM", "SIGHUP"] as const) {
  process.once(signal, () => {
    metro.stopAll().finally(() => process.exit(0))
    setTimeout(() => process.exit(0), 5000).unref()
  })
}
