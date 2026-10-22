# OpenCode Mobile

Native Android client for [opencode](https://opencode.ai). Connect your phone to the opencode
server running on your Mac over **Tailscale** and keep working from anywhere: read and resume
sessions, chat with live streaming, browse the project, view code with syntax highlighting, review
diffs, search the codebase, and get notified when a turn finishes.

Kotlin · Jetpack Compose · Material 3 · dark theme · minSdk 26.

---

## Features

| Area | What you get |
|---|---|
| Sessions | List/resume, new (opens it immediately), rename (long-press), delete, per-session folder picker, git branch bar (switch/create), status dots, tokens + cost |
| Chat | Live streaming (SSE, incremental), markdown + code highlighting, reasoning + tool cards, todos panel, copy message (long-press), queued indicator |
| Agents/models | Pick agent + model per message, plus the model's reasoning variant (low/high/max, when offered); selection remembered **per session** (last pick becomes the default for new sessions); per-message model name |
| Commands | `/` picker for opencode slash commands |
| Context | Header shows `N tokens · N% used · $N spent` (same formula as the TUI) |
| Files | Folder browser, code viewer with syntax highlighting, fuzzy file search + content grep |
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
  default = the model's own default). Long-press a message to copy. `± diff` under your
  message shows that turn's changes. `Abort` stops a running turn. Todos appear in a collapsible panel.
- **Header** — `N tokens · N% used · $N spent` for the current session (matches the TUI).
- **Files** — browse the project; search box switches between **Files** (fuzzy names) and **Content**
  (grep). Tap a result to open it with syntax highlighting.
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

## 5. Security

- All traffic runs over **Tailscale** (WireGuard, end-to-end encrypted). Nothing is exposed to the
  internet.
- Bind to the **Tailscale IP** (`--hostname 100.x.y.z`) so the server isn't reachable on your LAN.
- Always set `OPENCODE_SERVER_PASSWORD` — anyone who can reach the port otherwise gets full shell
  access to your Mac.
- For TLS, use `tailscale serve` and connect via `https://<mac>.ts.net`.
- Optionally restrict port `4096` to your own devices with Tailscale **ACLs**.
- The optional **OpenCode Go API key** is stored in the app's private DataStore and sent only to
  `opencode.ai` (never through the local server).

---

## 6. Troubleshooting

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
| Mac asleep = no connection | Use `caffeinate -s`, or the "prevent automatic sleeping" setting. |

---

## 7. Development

```bash
make build      # debug APK
make release    # release APK (R8/minified, ~1.8 MB)
make install    # install debug APK via adb
make serve      # local opencode test server on :4199 (password: secret)
make ship       # bump version, build, push main + v* tag (triggers CI release)
```

- Architecture, server-integration quirks, and the current roadmap: **`PROGRESS.md`**.
- Agent/contributor guide: **`AGENTS.md`**.
- Task tracking: `bd` (beads) — `bd ready`, `bd list`.
- CI: pushing a `v*` tag builds the release APK and creates a GitHub Release
  (`.github/workflows/build.yml`).

---

## License

Personal project. Use at your own risk.
