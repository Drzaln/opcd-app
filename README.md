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
| Sessions | List/resume, new, rename (long-press), delete, per-session folder picker, status dots, tokens + cost |
| Chat | Live streaming (SSE, incremental), markdown + code highlighting, reasoning + tool cards, todos panel, copy message (long-press), queued indicator |
| Agents/models | Pick agent + model per message; selection persisted per server; per-message model name |
| Commands | `/` picker for opencode slash commands |
| Context | Header shows `N tokens · N% used · $N spent` (same formula as the TUI) |
| Files | Folder browser, code viewer with syntax highlighting, fuzzy file search + content grep |
| Diffs | Session diff, per-message diff (tap `± diff` under a user message) |
| Offline | Room cache — sessions/messages show instantly, then refresh |
| Notifications | Background watch: notifies when a turn finishes or opencode needs input |
| OpenCode Go | Plan usage in Settings: paste your Go API key → 5-hour / weekly / monthly bars |
| Discovery | mDNS auto-detect on LAN + a Tailscale (remote) field |

---

## 1. Mac side — run opencode as a server

> Running the server on **Linux** instead? See **[README.linux.md](README.linux.md)**.

The phone talks to the same server the terminal TUI uses. The simplest way is to make the **TUI
itself** listen on a fixed port — then terminal and phone update each other in real time.

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
4. On the **Sessions** screen use the folder bar's **Change** to pick which project folder to view —
   sessions are scoped per folder.

Reopen the app later and it reconnects to the saved server automatically.

---

## 4. Using the app

- **Sessions** — tap to open, `+` for a new one, long-press for Rename/Delete, **Diff** for changes,
  folder bar to switch project, green/orange dot = idle/busy.
- **Chat** — type and send. `/` opens the slash-command picker. The agent/model chips above the input
  choose who answers (remembered per server). Long-press a message to copy. `± diff` under your
  message shows that turn's changes. `Abort` stops a running turn. Todos appear in a collapsible panel.
- **Header** — `N tokens · N% used · $N spent` for the current session (matches the TUI).
- **Files** — browse the project; search box switches between **Files** (fuzzy names) and **Content**
  (grep). Tap a result to open it with syntax highlighting.
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
| `401` / can't connect | Wrong password. It's the value of `OPENCODE_SERVER_PASSWORD`. |
| `Send failed: HTTP 400 …` | Update the app — an old build omitted the part `type` field. |
| Terminal doesn't update live | You must run the **TUI** with `--port` (not a separate `opencode serve`). |
| Input hidden behind keyboard | Fixed in current builds; update the app. |
| Can't see sessions from another folder | Use the folder bar (**Change**) on the Sessions screen. |
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
