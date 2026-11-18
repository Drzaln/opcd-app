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
| ✅ | **Device screen** — live H.264 view of any adb device (scrcpy) with touch and Back/Home/Recents/Dev menu |
| ✅ | **Emulators** — list the Mac's AVDs, start (through `avdslim` when installed) and stop them |
| ✅ | **iOS simulators** — list, start (slim or stock through `simslim` when installed, else `simctl`), stop; build & run (`expo run:ios` / `react-native run-ios`); the simulator's log; live screen with touch (idb) |

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
After that the phone reconnects on its own whenever the helper is running.

Optional tools (each feature appears in the app only when its tool is found):

| Tool | Enables |
|---|---|
| `adb` (Android platform-tools / Android Studio) | Android devices, Run, Logcat |
| `brew install scrcpy` | Live device screen |
| Android emulator (Android Studio) | Start/stop emulators |
| `avdslim` on PATH (e.g. Homebrew) | Low-memory emulator starts + slimming |
| Xcode (with an iOS simulator runtime) | iOS simulators, Run on them, their log |
| `brew install mobai-app/tap/simslim` | Slim iOS simulators (~4× less memory) |
| `brew install facebook/fb/idb-companion` | Live iOS simulator screen with touch |

Options: `--port 4097` (default), `--host <ip>` (repeatable; default = Tailscale IP + 127.0.0.1).

```bash
node dist/helper.mjs devices          # list paired phones
node dist/helper.mjs revoke <id|name> # unpair one
```

### Start it at login (optional)

Build once (`npm run build`), then save this as
`~/Library/LaunchAgents/dev.opencode.mobile.helper.plist` — replace the two paths with `which node`
and the absolute path of this folder's `dist/helper.mjs`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>dev.opencode.mobile.helper</string>
  <key>ProgramArguments</key>
  <array>
    <string>/opt/homebrew/bin/node</string>
    <string>/Users/you/opencode-android-app/helper/dist/helper.mjs</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict><key>PATH</key><string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string></dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>/tmp/opencode-mobile-helper.log</string>
  <key>StandardErrorPath</key><string>/tmp/opencode-mobile-helper.log</string>
</dict>
</plist>
```

```bash
launchctl load ~/Library/LaunchAgents/dev.opencode.mobile.helper.plist     # start now + at login
launchctl unload ~/Library/LaunchAgents/dev.opencode.mobile.helper.plist   # stop
```

The `PATH` entry lets the helper find `tailscale`, `scrcpy` and `avdslim` from Homebrew; `adb` and the
emulator are found in `~/Library/Android/sdk` either way. Pairing dialogs still appear on screen.

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
run.status  {root, tail?}  → {state, command, serial, errors, detected, detectError, detectedIos, detectIosError, lines[]}
run.start   {root, serial} → status   (expo run:android | react-native run-android | ./gradlew installDebug;
                                       a simulator UDID as serial → expo run:ios | react-native run-ios)
run.stop    {root}         → status
logcat.start {serial, mode: app|errors|all} → {id}     logcat.stop {id}   (a UDID → the simulator's os_log)
emu.list                   → {avds: [{name, serial}], avdslim, emulator, starting[]}
emu.start   {avd, headless?, cold?, ram?, slim?} → {avd, state, via}   emu.stop {serial}
sim.list                   → {sims: [{udid, name, runtime, state, booted, slim, memory}], simslim, xcode, starting[]}
sim.start   {udid, slim?, window?} → {udid, name, state, via}   sim.stop {udid}
screen.start {serial, maxSize?, bitRate?, maxFps?} → {width, height, deviceName}   (a UDID → idb)
screen.input {action, pointer, x, y, w, h} | {key: back|home|recents|menu|power}
screen.resize {width, height}    screen.stop
metro.status {root, tail?} → {state, kind, command, port, errors, detected, detectError, lines[]}
metro.start  {root, port?} → status   (port 8081 by default; detects Expo vs React Native)
metro.stop   {root}        → status
metro.reload {root}        → {via: "http" | "stdin"}
ping
notifications: lsp.message {session, message}, lsp.exit {session, code},
               metro.log {root, line: {seq, level, text, frame?}, replace},
               metro.status {root, state, …},
               run.log {root, line}, run.status {root, state, …},
               logcat.lines {id, lines: [{level, text}], skipped}, logcat.exit {id, code},
               emu.log {avd, text}, emu.status {avd, state, via, message},
               sim.log {udid, name, text}, sim.status {udid, name, state, via, message}, screen.end {reason}
binary frames (screen): [kind:1][ptsMicros:8][h264]   kind 1 config, 2 frame, 3 key frame
```

**Device screen** needs Homebrew's `scrcpy` (the helper uses its `scrcpy-server`, no extra npm
package). It speaks scrcpy 5's stream format: after the codec id, 12-byte *session* packets (bit 63:
flags, width, height — also on rotation) and media packets with bit 62 = config, bit 61 = key frame.
**Emulators** use `avdslim` when installed (`start <avd>` always named; `stop` with stdin closed so its
"save a Golden Snapshot? [y/N]" prompt takes the default), else the SDK `emulator`.

**iOS simulators** need Xcode (`xcrun --find simctl` must work; the helper then advertises `ios`).
`sim.list` reads `simctl list devices available -j` (iOS runtimes only — no watch/TV), and for booted
ones asks `simslim list --json --booted` whether they're slim (it can only tell while they run) and
their memory. Start with `slim` → `simslim on <udid>` (reconfigures while off, boots slim); without →
`simslim off <udid>` then `simslim boot` (stock); no simslim → `simctl boot` + `bootstatus -b`.
`window` opens Simulator.app on it. Stop → `simslim shutdown` / `simctl shutdown`, checked against
the list. Build & run with a UDID runs `expo run:ios --device <udid> --no-bundler` or
`react-native run-ios --udid <udid> --no-packager` (no `adb reverse`: the simulator shares the Mac's
localhost; `LANG` defaults to UTF-8 for CocoaPods). The log is `simctl spawn <udid> log stream
--style compact` with a predicate — App JS: `subsystem == "com.facebook.react.log"` or the app's
errors; Errors: errors/faults from processes in an installed app bundle; All: everything those
processes log — preceded by the last 5 minutes from `log show`, compacted to `HH:MM:SS L Process: …`.
**Simulator screen** (feature `simscreen`, when `idb_companion` is found): `screen.start` with a UDID
runs `idb_companion --udid <udid> --grpc-domain-sock <tmp>.sock --terminate-offline true` (one per
stream, killed on stop) and talks gRPC to it with `@grpc/grpc-js` and `proto/idb.proto` (the subset of
idb's protocol the helper uses — describe, hid, video_stream — field numbers unchanged). Video:
`video_stream` H.264 at `scale_factor = maxSize / longest side`, `avg_bitrate = bitRate`; each message
is one access unit, split into a config packet (SPS + PPS, only when they change) and a frame (kind 3
with an IDR slice), so the app's decoder is the same as for scrcpy; back-pressure pauses the gRPC
stream. Input goes over one long-lived `hid` stream, in points (`describe` gives the point size): touch
down/move = `press DOWN` at the point, up = `press UP`, one finger (the first pointer). Keys: `home` →
HOME button, `recents` → HOME twice 120 ms apart (the app switcher — a swipe from the bottom edge over
HID only scrolls the app), `back` → a drag in from the left edge, `menu` → `shake` (React Native's dev
menu). Measured against AXe's JPEG stream (Xcode 27, iPhone 17 sim): a screen change reached idb's
stream 220–490 ms after the trigger vs 600–670 ms for AXe, and a static screen costs ~0–40 KB/s vs
~1.1 MB/s.

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
           # adb devices/connect/pair, build & run and logcat against a fake adb + gradlew,
           # emulators (fake emulator + avdslim), iOS simulators (fake xcrun + simslim + idb_companion),
           # scrcpy control messages / packet parser, idb access-unit packing
```
