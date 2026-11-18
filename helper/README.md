# OpenCode Mobile helper (Mac)

Optional companion for the OpenCode Mobile app. opencode gives the app files, terminals and sessions;
the helper adds what opencode doesn't:

| Built | Feature |
|---|---|
| ✅ | **Pairing** — the phone shows a 4-digit code, the Mac asks you to Allow it |
| ✅ | **TypeScript language server** in the editor — completions, errors, hover, go to definition, project-wide rename, find references |
| ✅ | **Format on save** — Prettier (the project's own version and config), then `eslint --fix` when the project has ESLint |
| ⏳ | Metro start / stop / reload + logs |
| ⏳ | adb build & install, wireless pairing, logcat |
| ⏳ | Live emulator screen with touch |

The app only shows these features when the helper answers; everything else works without it.

## Run it

Requires Node 20+ and Tailscale on the Mac (the helper binds to the Mac's Tailscale IP, so it's
reachable from your tailnet but not from the local network).

```bash
cd helper
npm ci
npm start
```

Then in the app: **Settings → Mac helper → Pair**. The phone shows a code; a dialog on the Mac shows
the same code — press **Allow**. (No GUI session, e.g. over ssh? The terminal asks `y/N` instead.)

Options: `--port 4097` (default), `--host <ip>` (repeatable; default = Tailscale IP + 127.0.0.1).

```bash
node dist/helper.mjs devices          # list paired phones
node dist/helper.mjs revoke <id|name> # unpair one
```

### Keep it running (optional)

Create `~/Library/LaunchAgents/dev.opencode.mobile.helper.plist` pointing `ProgramArguments` at
`/usr/local/bin/node` (or `which node`) and the absolute path of `dist/helper.mjs`, with
`RunAtLoad` and `KeepAlive` set to true, then `launchctl load` it. Run `npm run build` once first.

## Security

- Every WebSocket needs `Authorization: Bearer <token>`. Tokens are 32 random bytes, issued only after
  you press Allow on the Mac, and stored hashed (SHA-256) in
  `~/.config/opencode-mobile-helper/devices.json` (mode 600).
- Only one pairing request can wait at a time, so nobody on the network can stack up dialogs.
- A paired phone can start a language server and run Prettier/ESLint in folders it names (absolute,
  existing, never `/`); format paths must stay inside that folder. Treat pairing like giving that
  phone your terminal — which the app already has through opencode.
- `OC_HELPER_AUTO_APPROVE=1` skips the dialog for automated tests and is ignored unless the helper
  listens on 127.0.0.1 only.

## Protocol (v1)

```
GET  /helper/info                    → { name, version, hostname, protocol, features }
POST /helper/pair/start {device}     → { requestId, code }
POST /helper/pair/wait  {requestId}  → { token, deviceId } | 403 denied | 408 nobody answered
WS   /helper/ws   Authorization: Bearer <token>
     → { id?, method, params }       ← { id, result } | { id, error: { message } }
     ← { method, params }            (notifications)

lsp.start {root} → {session}         spawns typescript-language-server --stdio in root
lsp.send  {session, message}         raw JSON-RPC to the server
lsp.stop  {session}
format    {root, path, text} → {text, changed, steps[], warnings[]}
ping
notifications: lsp.message {session, message}, lsp.exit {session, code}
```

The project's own TypeScript is used when it has one; otherwise the bundled TypeScript 5.9
(`typescript@7` is the native rewrite and has no `tsserver.js`, so it can't be the fallback).

## Tests

```bash
npm test   # pairing, auth refusal, LSP completions/diagnostics/definition through the relay, format
```
