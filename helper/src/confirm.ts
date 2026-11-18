// Asks the person at the Mac to approve a pairing request.
import { execFile } from "node:child_process"
import { createInterface } from "node:readline"

export type Approval = "allow" | "deny" | "timeout"

/**
 * macOS dialog via osascript (shows over everything, 60 s timeout). Falls back to a y/N prompt on the
 * terminal when there is no GUI session (ssh, launchd without Aqua).
 * OC_HELPER_AUTO_APPROVE=1 is honoured only for loopback-only test runs (see server.ts).
 */
export function confirmPairing(device: string, code: string, autoApprove: boolean): Promise<Approval> {
  console.log(`\n  Pairing request from "${device}" — code ${code}\n`)
  if (autoApprove) return Promise.resolve("allow")
  return new Promise((resolve) => {
    const text = `Allow "${device.replace(/["\\]/g, "")}" to use this Mac's OpenCode helper?\n\nCode on the phone: ${code}`
    const script = `display dialog ${JSON.stringify(text)} with title "OpenCode Mobile" buttons {"Deny", "Allow"} default button "Allow" cancel button "Deny" with icon caution giving up after 60`
    execFile("osascript", ["-e", script], { timeout: 65_000 }, (err, stdout) => {
      if (!err) return resolve(/gave up:true/.test(stdout) ? "timeout" : /button returned:Allow/.test(stdout) ? "allow" : "deny")
      // User pressed Deny -> osascript exits 1 with "User canceled". Anything else: no GUI, ask in the terminal.
      if (/User canceled|-128/.test(String(err.message))) return resolve("deny")
      terminalPrompt(code).then(resolve)
    })
  })
}

function terminalPrompt(code: string): Promise<Approval> {
  if (!process.stdin.isTTY) return Promise.resolve("deny")
  return new Promise((resolve) => {
    const rl = createInterface({ input: process.stdin, output: process.stdout })
    const timer = setTimeout(() => { rl.close(); resolve("timeout") }, 60_000)
    rl.question(`  Does the phone show ${code}? Allow pairing [y/N] `, (answer) => {
      clearTimeout(timer)
      rl.close()
      resolve(/^y(es)?$/i.test(answer.trim()) ? "allow" : "deny")
    })
  })
}

/** Best-effort desktop notification (no-op where unavailable). */
export function notify(title: string, message: string) {
  execFile("osascript", ["-e", `display notification ${JSON.stringify(message)} with title ${JSON.stringify(title)}`], () => {})
}
