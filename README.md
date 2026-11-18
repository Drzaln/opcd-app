# OpenCode Mobile

Native Android client for [opencode](https://opencode.ai). Connect your phone to the opencode
server running on your Mac over **Tailscale** and keep working from anywhere: **write code yourself in
a real editor that saves to the Mac**, read and resume sessions, chat with live streaming, browse the
project, review diffs, commit, search the codebase, and get notified when a turn finishes.

Kotlin · Jetpack Compose · Material 3 · dark theme · minSdk 26.

---

## Features

| Area | What you get |
|---|---|
| Code | **CodeMirror 6 editor that saves to your Mac**: tabs with dirty dots, undo per tab, syntax highlighting (TS/TSX, JS/JSX, JSON, Kotlin + Gradle KTS, Markdown, CSS, YAML, XML), bracket matching + auto-close, auto-indent, soft wrap, find/replace, go to line, coding key row above the keyboard, hardware shortcuts (Ctrl+S/P/F/G, Ctrl+/), "Go to file" palette, recent files. Saves are verified by SHA-256; if the file changed on the Mac you get **Reload / Overwrite / Show diff** instead of a silent overwrite |
| Code (tablet / landscape) | File tree │ editor │ Terminal / Git / Metro / Android bottom panel side by side (≥ 720 dp); live device screen in a right-hand column (≥ 1000 dp, with the Mac helper) |
| Git | Branch + "N changed" in the editor, Changes list with per-file diffs, **Commit…** (`git add -A && git commit`, no push) |
| Mac helper (optional) | A small companion you run on the Mac (`helper/`, see [section 6](#6-mac-helper-optional)): **TypeScript** completions, errors, go to definition, rename, find references; **format on save** (Prettier/ESLint); **Metro** start/stop/reload with live logs; **Android** devices — build & run, wireless connect, logcat; a **live device screen** with touch; **start/stop the Mac's emulators** (uses `avdslim` when installed); **iOS simulators** on the Mac — start/stop (slim with `simslim`), build & run, logs, live screen with touch |
| Sessions | List/resume, new (opens it immediately), rename (long-press), delete, per-session folder picker, git branch bar (switch/create), status dots, tokens + cost |
| Chat | Live streaming (SSE, incremental), markdown + code highlighting, reasoning + tool cards, todos panel, copy message (long-press), queued indicator, image/file attachments (inline; tap an image to zoom) |
| Agents/models | Pick agent + model per message, plus the model's reasoning variant (low/high/max, when offered); selection remembered **per session** (last pick becomes the default for new sessions); per-message model name |
| Commands | `/` picker for opencode slash commands |
| Context | Header shows `N tokens · N% used · $N spent` (same formula as the TUI) |
| Files | Folder browser, read-only code viewer (long-press → View read-only), fuzzy file search + content grep |
| Terminal | Full PTY + two renderers: **xterm.js** (scrollback, mouse, reflow, hardware keys) or the native canvas; paste/copy |
| Diffs | Session diff, per-message diff (tap `± diff` under a user message) |
| Offline | Room cache — sessions/messages show instantly, then refresh |
| Notifications | Background watch: notifies when a turn finishes or opencode needs input |
| OpenCode Go | Plan usage in Settings: paste your Go API key → 5-hour / weekly / monthly bars |
| Connection | Live/Checking/Offline chip in the app bar (tap for version + latency + recheck) |
| Discovery | mDNS auto-detect on LAN + a Tailscale (remote) field |

> **opencode 2.0 note:** the app auto-detects the server API generation (1.x or 2.0). On 2.0 a few
> things differ because the server dropped them: **content grep** (the *Content* search tab returns
> nothing), the **todos panel** stays empty, and **share/unshare session links** are unavailable
> (share links were removed server-side). Everything else — sessions, chat, files, diffs, terminal,
> permissions, agent questions — works on both.

---

## 1. Mac side — run opencode as a server

> Running the server on **Linux** instead? See **[README.linux.md](README.linux.md)**.

The app supports **both opencode 1.x and 2.0** (it auto-detects the API generation). How you expose
the server depends on your opencode version.

### opencode 2.0 (recommended)

In 2.0 the TUI is a *client* of a shared background service, so expose that service and both the TUI
and the phone become clients of the same event bus (live sync both ways). Configure it once from a
plain terminal (each `service set` restarts the server, so don't run it inside an opencode session):

```bash
opencode service set hostname 0.0.0.0        # or your Tailscale IP 100.x.y.z
opencode service set port 4096
opencode service set password your-secret
opencode service start
```

Then just run plain `opencode` (the TUI) — it discovers and authenticates to that same service.
`opencode service get` shows the config; `service unset <key>` reverts a setting. For an isolated
server instead: `OPENCODE_SERVER_PASSWORD=secret opencode serve --hostname 0.0.0.0 --port 4096`
(separate process → its own event bus; point clients at it with `opencode --server http://127.0.0.1:4096`).

### opencode 1.x

Make the **TUI itself** listen on a fixed port — then terminal and phone update each other in real time.

```bash
# 1. Find your Tailscale IP
tailscale ip            # e.g. 100.111.24.2

# 2. Run your normal TUI, bound to the tailnet only, password protected, kept awake
OPENCODE_SERVER_PASSWORD=your-secret caffeinate -s \
  opencode --hostname 100.111.24.2 --port 4096
```

- `--hostname 100.x.y.z` → listens **only** on Tailscale (nothing exposed on your home WiFi).
- `--port 4096` → fixed port the phone connects to.
- `OPENCODE_SERVER_PASSWORD` → required; the server returns `401` without it.
- `caffeinate -s` → keeps the Mac awake (or enable
  *System Settings → Battery → Options → Prevent automatic sleeping on power adapter*).

> **Why not `opencode serve` / `opencode web`?** A separate `serve` process has its own event bus, so
> the TUI and phone won't sync live. `web` also opens a browser. Run the **TUI** with `--port`.

### Optional — HTTPS with Tailscale Serve

```bash
tailscale serve --bg 4096
```
Then connect the app to `https://<mac-hostname>.ts.net` (public TLS cert, creds never plaintext).

### Optional — mDNS auto-detect on the same LAN

Add `--mdns` (note: it forces hostname `0.0.0.0`, i.e. also on your LAN):
```bash
OPENCODE_SERVER_PASSWORD=your-secret opencode --hostname 0.0.0.0 --port 4096 --mdns
```
The app's **Auto-detect** then finds it as `opencode-4096`.

Keep this terminal open. Closing it stops the server. Mac powered off/asleep → no connection.

---

## 2. Install the app

**Option A — download the APK** (easiest): open
`https://github.com/Drzaln/opcd-app/releases`, download `app-release.apk` from the latest release,
and install it (allow "install unknown apps" for your browser/file manager).

**Option B — build from source:**
```bash
git clone git@github.com:Drzaln/opcd-app.git
cd opcd-app
make build          # → app/build/outputs/apk/debug/app-debug.apk
make install        # adb install (device connected via USB / wireless debugging)
```
Requirements: JDK 17, Android SDK 36, Gradle 8.13 (wrapper included).

> Debug builds and release builds use different signing keys. If you switch between them you may need
> `adb uninstall dev.opencode.mobile` once.

---

## 3. First-time app setup

1. Turn on **Tailscale** on the phone.
2. Open the app → **Connect to server**:
   - If you're on the same LAN and started opencode with `--mdns`: tap **Auto-detect** → pick
     `opencode-4096`.
   - Otherwise use the **Tailscale (remote)** field: enter `100.111.24.2` (or `my-mac.ts.net` for
     `tailscale serve`), port `4096`, and the password.
3. Tap **Connect**. The server is saved; it becomes your active server.
4. On the **Sessions** screen tap the folder bar to pick which project folder to view — sessions are
   scoped per folder. The picker is a scrollable sheet with a filter box (appears when you have many
   projects), a checkmark on the active folder, "Server default", and "Use a custom path".

Reopen the app later and it reconnects to the saved server automatically.

---

## 4. Using the app

Screens use standard Android navigation animations (slide in/out on push, reverse on back).

- **Sessions** — tap to open, `+` for a new one (it opens straight away), long-press for
  Rename/Delete, **Diff** for changes,
  folder bar to switch project (tappable card showing the folder name + shortened path; the picker
  sheet lists detected projects with the active one check-marked and filters as the list grows),
  branch bar under the folder to see the current git branch and switch/create branches,
  green/orange dot = idle/busy.
- **Chat** — type and send. `/` opens the slash-command picker. The agent/model chips above the input
  choose who answers (remembered **per session**; your last pick becomes the default for new sessions).
  When the model exposes reasoning variants, a **Variant** chip appears next to it (e.g. low/high/max;
  default = the model's own default). The chip row scrolls horizontally; the selected model uses its
  short name, with the full `provider · model` label in the dropdown. Use the attach button to add
  photos or files; images render inline in the transcript — tap one for fullscreen pinch-zoom.
  Long-press a message to copy. `± diff` under your message shows that turn's changes. `Abort` stops a
  running turn. Todos appear in a collapsible panel.
- **Header** — `N tokens · N% used · $N spent` for the current session (matches the TUI).
- **Code** (first tab) — write code on the phone; see [section 5](#5-writing-code-on-the-phone).
- **Mac helper features** (TypeScript, Metro, Android devices, device screen) — see
  [section 6](#6-mac-helper-optional).
- **Files search** — the search box switches between **Files** (fuzzy names) and **Content** (grep).
- **Terminal** — open from the Sessions screen. Two renderers, switch with the **⋮** in the top bar:
  **xterm.js** (default — real xterm behaviour: scrollback you can fling, mouse clicks/scroll in
  `vim`/`tmux`/`htop`, box-drawing, bracketed paste, hardware keyboards) and **Native** (the in-app
  Compose canvas, lighter, no WebView). Tap it to type; the key row gives esc/tab/arrows/^C etc.
  **Paste** sends your clipboard to the shell; **Copy** copies the selection or the visible screen.
- **Notifications** — Servers screen → toggle **Notify when a turn finishes**, grant the permission.
  You'll get a notification when opencode finishes or needs input, even with the app backgrounded.
- **OpenCode Go usage** (optional) — Settings → **OpenCode Go** → paste your Go API key (copy it from
  the [Zen console](https://opencode.ai/auth)). The app then shows your 5-hour / weekly / monthly
  plan usage with reset times. The key is stored on-device and only sent to `opencode.ai`.

---

## 5. Writing code on the phone

1. Open the **Code** tab (first tab). You see the project's file tree with **Recent** files on top.
   (Pick the project folder on the Sessions screen first — the Code tab follows it.)
2. **Tap a file** to edit it. Long-press → **View read-only** if you only want to look.
3. **Type** with the soft keyboard. The key row above it has `Tab { } ( ) [ ] < > = ' " ; ← →` and
   undo; brackets and quotes pair up, Enter keeps the indent. With a hardware keyboard: Ctrl+S save,
   Ctrl+P go to file, Ctrl+F find, Ctrl+G go to line, Ctrl+/ toggle comment.
4. **Save** (or Ctrl+S) writes the file on the Mac and checks it arrived intact (SHA-256). The tab shows
   *Saving… → Saved*, or *Failed: reason*.
5. If the file changed on the Mac since you opened it (often the agent), you're asked:
   **Show diff** (compare), **Reload** (take the Mac's version) or **Overwrite** (keep yours).
6. **Git:** tap the `branch · N changed` line → the changed files, their diffs, and **Commit…**
   (`git add -A && git commit`; pushing is up to you on the Mac).

More: the search icon / Ctrl+P is **Go to file**; ⋮ has find/replace, go to line, soft wrap and toggle
comment. Several files open as tabs (a dot marks unsaved ones). File links in chat and diffs open the
editor at that line. Files over 2 MB and binary files open read-only.

**Tablet or landscape phone (≥ 720 dp):** Code becomes a small IDE — file tree │ editor │ a bottom
panel with **Terminal** and **Git** (plus **Metro** and **Android** when the Mac helper runs). On
≥ 1000 dp a right-hand column shows the live device screen.

---

## 6. Mac helper (optional)

The app talks to opencode for everything above. The **helper** is a small extra program on the same
Mac that adds IDE features opencode doesn't have. The app only shows these features while the helper
is running and paired; everything else works without it.

### What each feature needs on the Mac

| Feature | Needs |
|---|---|
| TypeScript completions/errors, format on save | Node 20+ (the project's own TypeScript/Prettier/ESLint are used when present) |
| Metro | A React Native or Expo project (`npm install` done) |
| Android: devices, Run, Logcat | Android platform-tools (`adb`) — comes with Android Studio |
| Device screen | `brew install scrcpy` |
| Start/stop emulators | The Android emulator (Android Studio); `avdslim` if you want slim, low-memory emulators |
| iOS simulators: start/stop, Run, Logs | Xcode with an iOS simulator runtime (`xcode-select` pointing at Xcode, not just the Command Line Tools); CocoaPods for Expo/React Native builds; `simslim` (`brew install mobai-app/tap/simslim`) if you want slim, low-memory simulators |
| iOS simulator screen | `brew install facebook/fb/idb-companion` (Meta's idb companion) |

### Set up once

1. On the Mac, in this repository:
   ```bash
   cd helper
   npm ci
   npm start
   ```
   It prints `listening on http://100.x.y.z:4097` — your Mac's **Tailscale** address (it doesn't listen
   on your Wi-Fi/LAN). Leave it running. (To start it at login, see `helper/README.md`.)
2. On the phone: **Settings → Mac helper → Pair**. The phone shows a 4-digit code.
3. On the Mac, a dialog shows the **same code** — click **Allow**. (Over ssh, the terminal asks `y/N`.)
4. Settings → Mac helper now says **Connected**. That's it — the phone reconnects by itself from now
   on. To unpair: Settings → Mac helper → **Unpair** (or `node dist/helper.mjs revoke <name>` on the
   Mac).

### TypeScript and format on save

Open a `.ts`/`.tsx`/`.js`/`.jsx` file. The status line reads *TypeScript · LSP ready*. Completions pop
up as you type, errors are underlined (⋮ → **Problems** lists them), and ⋮ has **Go to definition**,
**Rename symbol** (every file that uses it opens as a tab — save them), **Find references** and
**Format document**. Saving runs Prettier (and `eslint --fix` if the project uses ESLint) first; turn
that off in Settings → Mac helper → **Format on save**.

### Metro (React Native / Expo)

1. Open the project folder, then the **Metro** button in the Code tab's top bar (phone) or the
   **Metro** tab in the bottom panel (tablet/landscape).
2. **Start** runs `expo start` or `react-native start` on the Mac (detected from `package.json`).
3. The log streams live: warnings orange, errors red (a red badge appears if one arrives while you're
   elsewhere). **Problems** shows only warnings and errors.
4. **Reload** reloads the app on the connected device(s); **Stop** ends Metro.

Metro keeps running on the Mac if the phone drops off; reopening catches up on the log.

### Android devices: Run and Logcat

1. Tap the **Android** button (phone) or tab (tablet/landscape). The top row picks the device: USB
   devices and emulators on the Mac are listed automatically.
2. **Run** builds the app on the Mac and installs it on that device (`expo run:android`,
   `react-native run-android` or `./gradlew installDebug`), streaming the build output. For React
   Native / Expo, start Metro first — Run connects the device to it (`adb reverse`).
3. **Logcat** shows the device's log: **App JS** (your `console.log`, warnings, errors, crashes),
   **Errors** (everything at error level) or **All**, with a text filter.

**Wireless device** (Android 11+, phone on Wi-Fi): on that phone open Developer options → **Wireless
debugging** → **Pair device with pairing code**. In the app: **Connect…** → enter the pairing port and
code shown there, plus the IP:port from the Wireless debugging screen (the host is prefilled with this
phone's Tailscale address) → **Pair & connect**. Next time just **Connect**.

### Device screen

In the Android panel open **Screen** (on a tablet it's the right-hand column). The selected device's
screen appears live: tap and swipe on it; the buttons above are **Back · Home · Recents · Dev menu ·
Stop**. The stream stops when you leave the tab. If the selected device is the phone you're holding,
it doesn't start by itself (it would show this screen inside itself).

### Emulators on the Mac

Open the device list (top row of the Android panel). Under **Emulators on the Mac**:

- **Start <name>…** → choose **Headless** (no window on the Mac — recommended when you watch it
  through the Screen tab), **Cold boot**, **Slim after boot** (only with `avdslim`) and **RAM** →
  **Start**. When it has booted it's selected automatically.
- **Stop <name>** shuts a running emulator down.

With `avdslim` installed the helper uses it (low-memory host flags, and slimming after boot if you
leave that on); without it, the plain Android `emulator`.

### iOS simulators on the Mac

The Android panel handles iOS simulators too (the Mac must have Xcode). Open the device list: under
**iOS simulators on the Mac**:

- **Start <name>…** → choose **Slim** (only with `simslim`: turns off ~170 background services the
  simulator doesn't need for development — Siri, Spotlight, iCloud… — for about 4× less memory; off
  boots it stock) and **Show the Simulator window on the Mac** → **Start**. When it's up it's
  selected automatically.
- A running simulator appears among the devices as **<name> · iOS** (with *slim*/*stock* and its
  memory when `simslim` is installed).
- **Run** builds the app on the Mac and installs it on the simulator (`expo run:ios` or
  `react-native run-ios`; plain Xcode projects aren't supported). Start Metro first — the simulator
  reaches it on the Mac directly. The first build runs CocoaPods and Xcode and takes a few minutes.
- **Logs** shows the simulator's log for your app: **App JS** (your `console.log` / warnings, plus
  errors from the app), **Errors** (the app's errors) or **All** (everything your app logs).
- **Stop <name>** shuts the simulator down.

- **Screen** shows the simulator live (with `idb-companion` installed): tap, scroll and swipe on it.
  The buttons above mean: **Back** = swipe in from the left edge, **Home**, **Recents** = the app
  switcher, **Dev menu** = shake (opens React Native's dev menu).

Switching a simulator between slim and stock takes a little longer (simslim reconfigures it while
it's off). One finger only on the simulator screen (no pinch).

### Good to know

- A Metro **Reload** only reaches an app that is in the foreground on its device. If the app and the
  editor are on the same phone, switch to the app, then reload.
- The first install of a debug build may trigger a **Google Play Protect** prompt on the device —
  answer it there.
- Everything goes over Tailscale; a formatted save over 4G can take a few seconds.
- Full details, security notes and the wire protocol: [`helper/README.md`](helper/README.md).

---

## 7. Security

- All traffic runs over **Tailscale** (WireGuard, end-to-end encrypted). Nothing is exposed to the
  internet.
- Bind to the **Tailscale IP** (`--hostname 100.x.y.z`) so the server isn't reachable on your LAN.
- Always set `OPENCODE_SERVER_PASSWORD` — anyone who can reach the port otherwise gets full shell
  access to your Mac.
- For TLS, use `tailscale serve` and connect via `https://<mac>.ts.net`.
- Optionally restrict port `4096` to your own devices with Tailscale **ACLs**.
- The **Mac helper** listens on the Tailscale address only. A phone gets a token only after you click
  **Allow** on the Mac; tokens are stored hashed and can be revoked (`node dist/helper.mjs revoke`).
  A paired phone can run builds, Metro and adb on your Mac — treat pairing like giving it a terminal
  (which the app already has through opencode).
- The optional **OpenCode Go API key** is stored in the app's private DataStore and sent only to
  `opencode.ai` (never through the local server).

---

## 8. Troubleshooting

| Symptom | Fix |
|---|---|
| `401` / can't connect | Wrong password. It's the value of `OPENCODE_SERVER_PASSWORD` (or `opencode service get password`). |
| `Unexpected JSON token … had '<'` after upgrading opencode | Old build hitting the 2.0 web UI on old paths. Update the app — it now auto-detects 1.x vs 2.0. |
| `Send failed: HTTP 400 …` | Update the app — an old build omitted the part `type` field. |
| Terminal doesn't update live | You must run the **TUI** with `--port` (not a separate `opencode serve`). |
| Terminal is blank / no prompt (xterm.js renderer) | Fixed in v0.1.49 — the page was loading before the WebView had a size, which collapsed the terminal to 1 row. Update the app. |
| xterm.js banner appears | It names the cause (JS error, load error, or "never reported ready"). Tap **Use native renderer** to keep working, and its text is worth reporting. |
| Input hidden behind keyboard | Fixed in current builds; update the app. |
| Can't see sessions from another folder | Tap the folder bar on the Sessions screen and pick the project folder. |
| Auto-detect only finds the LAN IP | mDNS advertises the LAN address; use the **Tailscale (remote)** field instead. |
| Notifications silent | Grant notification permission; re-toggle the switch; check Android battery settings. |
| "App not installed" on update | Signing-key mismatch (debug vs release). `adb uninstall dev.opencode.mobile`, then install. |
| Editor says "needs WebView Chromium 80+" | Update **Android System WebView** from the Play Store; until then files open read-only. |
| Save says "File changed on the Mac" | Someone (often the agent) edited it after you opened it. **Show diff** to compare, then **Reload** (take theirs) or **Overwrite** (keep yours). |
| Settings → Mac helper says *Not running* | Start it on the Mac (`cd helper && npm start`) and check Tailscale is on on both sides. The helper must run on the same Mac as opencode. |
| Mac helper: *Pairing was revoked* | The Mac forgot this phone (revoked, or `~/.config/opencode-mobile-helper` deleted). Pair again. |
| No **Android** / **Screen** / emulators in the app | The helper didn't find `adb` / `scrcpy` / the emulator. Install them (see section 6), restart the helper. |
| No iOS simulators in the device list | The helper needs Xcode: `xcrun simctl list` must work on the Mac (`sudo xcode-select -s /Applications/Xcode.app` if it points at the Command Line Tools). Restart the helper after installing. |
| Simulator **Screen** says it needs idb | `brew install facebook/fb/idb-companion`, then restart the helper. |
| Simulator **Screen** says *idb_companion exited* | The simulator isn't booted (start it from the device list) or Xcode was updated — `brew upgrade idb-companion`. |
| iOS Run fails at CocoaPods | Install it (`brew install cocoapods`) and check the Run log; the first `expo run:ios` also needs `npm install` done. |
| Screen says *Screen stream didn't start* | Update scrcpy (`brew upgrade scrcpy`) and restart the helper; make sure the device shows as *device* (not *unauthorized*) in the list. |
| Run fails before building | Open the build log in the **Run** view; most often missing `npm install`, a missing Android SDK, or an unauthorized device. |
| Mac asleep = no connection | Use `caffeinate -s`, or the "prevent automatic sleeping" setting. |

---

## 9. Development

```bash
make build      # debug APK
make release    # release APK (R8/minified, ~1.8 MB)
make install    # install debug APK via adb
make serve      # local opencode test server on :4199 (password: secret)
make ship       # bump version, build, push main + v* tag (triggers CI release)
```

- Unit tests: `./gradlew :app:testDebugUnitTest` (path validation, base64 chunking, git parsing,
  logcat formatting, client caching). Helper: `cd helper && npm test`.
  Against a live `make serve`: `OC_IT=1 ./gradlew :app:testDebugUnitTest` also runs the save/read
  round trips (native route + PTY writer).
- Editor bundle (CodeMirror 6): `cd tools/editor-bundle && npm ci && npm run build` → regenerates
  `app/src/main/assets/editor/editor.js` + `NOTICE.txt` (committed; npm is not part of Gradle).
- Architecture, server-integration quirks, and the current roadmap: **`PROGRESS.md`**.
- Agent/contributor guide: **`AGENTS.md`**.
- Task tracking: `bd` (beads) — `bd ready`, `bd list`.
- CI: pushing a `v*` tag builds the release APK and creates a GitHub Release
  (`.github/workflows/build.yml`).

---

## License

Personal project. Use at your own risk.
