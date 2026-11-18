# OpenCode Mobile helper (Mac)

Optional companion for the OpenCode Mobile app. opencode gives the app files, terminals and sessions;
the helper adds what opencode doesn't:

| Built | Feature |
|---|---|
| ✅ | **Pairing** — the phone shows a 4-digit code, the Mac asks you to Allow it |
| ✅ | **TypeScript language server** in the editor — completions, errors, hover, go to definition, project-wide rename, find references |
| ✅ | **Format on save** — Prettier (the project's own version and config), then `eslint --fix` when the project has ESLint |
| ✅ | **Metro** — start / stop / reload for React Native and Expo projects, live logs with errors highlighted |
| ✅ | **Android** — the Mac's adb devices, wireless pair/connect, build & run on a device, logcat |
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
- A paired phone can start a language server, run Prettier/ESLint, and start Metro (the project's own
  `expo` / `react-native` CLI from `node_modules/.bin`, else `npx --no-install`) in folders it names
  (absolute, existing, never `/`); format paths must stay inside that folder. Treat pairing like giving that
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
adb.devices                → {adb, devices: [{serial, state, model, transport}]}
adb.pair    {host, port, code} / adb.connect {host, port} / adb.disconnect {serial} / adb.reverse {serial, port?}
run.status  {root, tail?}  → {state, command, serial, errors, detected, detectError, lines[]}
run.start   {root, serial} → status   (expo run:android | react-native run-android | ./gradlew installDebug)
run.stop    {root}         → status
logcat.start {serial, mode: app|errors|all} → {id}     logcat.stop {id}
metro.status {root, tail?} → {state, kind, command, port, errors, detected, detectError, lines[]}
metro.start  {root, port?} → status   (port 8081 by default; detects Expo vs React Native)
metro.stop   {root}        → status
metro.reload {root}        → {via: "http" | "stdin"}
ping
notifications: lsp.message {session, message}, lsp.exit {session, code},
               metro.log {root, line: {seq, level, text, frame?}, replace},
               metro.status {root, state, …},
               run.log {root, line}, run.status {root, state, …},
               logcat.lines {id, lines: [{level, text}], skipped}, logcat.exit {id, code}
```

**Android** needs adb (Android platform-tools; found via `ANDROID_HOME`, `~/Library/Android/sdk`,
Homebrew or PATH) — without it the helper doesn't advertise `adb` and the app hides the panel. Build &
run belongs to the helper (a 4G drop doesn't cancel a Gradle build) and runs `adb reverse tcp:8081`
first so the app reaches Metro. Logcat streams belong to the phone connection, start with the filtered
backlog (`logcat -d`), then stream live in 250 ms batches (max 300 lines; the rest are reported as
skipped). Wireless debugging is Android 11+ and Wi-Fi only on the phone: pair once with the 6-digit
code, then connect to the IP:port the phone shows.

**Metro** runs per project root and belongs to the helper, not to a phone connection: a 4G drop
doesn't stop the bundler, and a phone that reconnects calls `metro.status` to get the state and the
last 500 log lines (2000 are kept). States: `stopped`, `starting` (until `GET /status` answers
`packager-status:running`), `running`, `external` (something else already serves that port — reload
works, logs don't), `exited` (crashed). Detection reads `package.json`: an `expo` dependency →
`expo start` (unless the start script is `react-native start`), else `react-native` →
`react-native start`, else it refuses. Output is split on CR/LF with ANSI stripped; the bundling
progress line is collapsed into one entry (`replace: true`); lines are classified `info` / `warn` /
`error`, and code-frame lines under an error carry `frame: true` (same level, not counted again).
Reload broadcasts `reload` on the dev server's `/message` socket like Metro's `r` key (Expo has no
`POST /reload`); falls back to `/reload`, then `r` on stdin. Stop kills the whole process group;
the helper stops every Metro when it exits. `CI` isn't set (Expo disables reloads in CI mode).

The project's own TypeScript is used when it has one; otherwise the bundled TypeScript 5.9
(`typescript@7` is the native rewrite and has no `tsserver.js`, so it can't be the fallback).

## Tests

```bash
npm test   # pairing, auth refusal, LSP completions/diagnostics/definition through the relay, format,
           # Metro detect/start/logs/reload/stop against a stand-in `react-native start`,
           # adb devices/connect/pair, build & run and logcat against a fake adb + gradlew
```
