<!-- ship: v0.1.27 (versionCode 28) -->

# PROGRESS — OpenCode Mobile (Android)

**What:** native Kotlin/Compose Android client for an opencode server (Mac). Sessions, live chat
(SSE), files + code viewer, diffs, search, offline cache, notifications.

**Repo:** git@github.com:Drzaln/opcd-app.git · **Package:** `dev.opencode.mobile` · single `:app` module.

**Stack (pinned, don't bump blindly):** Kotlin 2.2.20 · AGP 8.13.0 · Gradle 8.13 · compose-bom
2025.09.00 · minSdk 26 · compile/target 36 · Retrofit 2.11 · OkHttp 4.12 · kotlinx.serialization
1.7.3 · Room 2.8.5 · Prism4j 2.0.0 · java-diff-utils 4.12 · DataStore 1.1.1.

**Build/ship:** `make build` · `make release` · `make ship` (`scripts/ship.sh` bumps version, builds,
pushes `main`, tags `v*`; CI builds + GitHub Release). Say "ship it" → always run `make ship`
after updating this file.

## Architecture (where things live)

- `OpenCodeApp` — owns `ServerStore` (prefs), `OpenCodeRepository` (HTTP/SSE), `CacheStore` (Room).
- `MainActivity` — NavHost + `Routes` (servers→sessions→chat/files/file/diff).
- `AppViewModel` — servers list, active server, `currentDirectory`, notifications toggle, `probe()`.
- `data/model/Dtos.kt` — all kotlinx DTOs.
- `data/net/`: `OpenCodeApi` (Retrofit), `OpenCodeRepository` (client cache + SSE + reconnect),
  `ServerStore` (DataStore), `NsdDiscovery` (mDNS).
- `data/cache/CacheStore.kt` — Room key/json cache (sessions, messages).
- `notify/` — notification channels + `SessionWatchService` (foreground SSE watcher).
- `ui/common/` — `CodeHighlighter` (Prism4j), `Markdown`, `DiffLines`, `CodeBlock`.
- `ui/{servers,sessions,chat,files,file,diff}/` — screens + their ViewModels.

## Server integration (hard-won facts)

1. **Instance routing:** pass `?directory=<abs path>` on nearly every call (sessions, messages,
   prompt_async, command, diff, abort, todos, file list/content, status, SSE). Global: `/global/health`,
   `/project`. The app keeps `AppViewModel.currentDirectory` (folder picker on Sessions screen).
2. **SSE:** event type is inside the JSON body — `data: {"type":"...","properties":{...}}` — NOT the SSE
   `event:` field. `/event` also takes `?directory=`.
3. **Prompt body:** must include the part discriminator. Json config is
   `ignoreUnknownKeys=true; explicitNulls=false; encodeDefaults=true` (the last two are required —
   `type` has a default and would otherwise be omitted → HTTP 400 "Expected { readonly type ... }").
   Body: `{"parts":[{"type":"text","text":"..."}]}`.
4. **Session diff:** `/session/{id}/diff[?messageID=]` returns `FileDiff{file,before,after,additions,
   deletions}` where `before`/`after` are FULL file contents → compute line diffs client-side
   (`ui/common/DiffLines.kt`). `messageID` must be a **user** message (server requires `role==="user"`).
5. **Realtime both ways:** the Mac must run the **TUI on a fixed port**
   (`OPENCODE_SERVER_PASSWORD=… opencode --hostname <ip> --port 4096`). A separate `opencode serve`
   is a different process/event-bus → no live TUI↔app sync. (Linux box: see `README.linux.md`.)
6. **Auth:** HTTP Basic (`opencode` user + password). 401 otherwise.
7. **Totals formula** (matches TUI `sidebar/context.tsx`): tokens = last assistant message
   `input+output+reasoning+cache.read+cache.write`; % = tokens / model `limit.context`;
   $ = `session.cost`.
8. **Polymorphic fields:** an assistant message's `summary` is a **boolean** (`true`), a user
   message's is an object — the DTO types it as `JsonElement`. Messages are also decoded
   **per-item** (bad entries skipped) so one mismatch can't break the whole chat.
9. **Message pagination:** `GET /session/{id}/message?limit=N` returns the newest N messages
   (ascending) and sets `X-Next-Cursor` (+ `Link rel=next`) when older ones exist. Next page:
   `?limit=N&before=<cursor>` (opaque base64 cursor; **`before` requires `limit` or → 400**).
   Cursor is a message id+timestamp; `X-Next-Cursor` is absent when there's no more. Chat loads
   PAGE_SIZE=50 and keeps already-loaded older pages when refreshing.
10. **Permissions:** live event is `permission.asked` on 1.18.31 (older servers emit
    `permission.updated` with a different shape: `title`/`pattern` instead of
    `permission`/`patterns`). Reply: legacy `POST /session/{id}/permissions/{permissionID}`
    `{"response":"once"|"always"|"reject"}` (works on 1.18.31, primary) → fallback
    `POST /permission/{requestID}/reply` `{"reply":…}`. Pending list: `GET /permission`
    (404s on older servers → ignored). Replied event: `permission.replied`.
11. **File/attachment parts:** `{"type":"file","mime","filename","url"}` with a
    `data:<mime>;base64,…` URL for phone-local files — accepted by `prompt_async` and persisted.
    Server-side file parts come back with `source.path` (openable) or a `data:` URL (rendered).
12. **Questions (the agent's `ask` tool):** live event `question.asked` with
    `{id, sessionID, questions:[{question, header, options:[{label,description}], multiple?, custom?}],
    tool}`. Pending: `GET /question`. Answer: `POST /question/{id}/reply {"answers":[["label",…],…]}`
    (one string-array per question, in order). Dismiss: `POST /question/{id}/reject` (no body).
    Events `question.replied` / `question.rejected` carry `requestID`. (Perms and questions are
    separate blocking prompts — the app renders whichever arrives first.)
13. **Session actions:** `POST /session/{id}/fork` `{messageID?}` → new `Session`;
    `POST /session/{id}/revert` `{messageID}` and `POST /session/{id}/unrevert` → `Session`
    (a non-null `session.revert` means a revert is staged); `DELETE /session/{id}/message/{messageID}`.
    Share: `POST/DELETE /session/{id}/share` → `Session.share = {url}` (host `opncd.ai`).
    **Quirk:** on 1.18.31 `unshare` returns 200 but `session.share` stays populated — mask it locally.
14. **Battery:** polling is skipped while the SSE stream is live (any event, incl. `server.heartbeat`,
    within 45 s), with a 5-minute safety reconcile. Sessions/files screens are on-demand only.
15. **Terminal (PTY):** `GET/POST /pty` (create body `{command?,args?,cwd?,title?,env?}` → default
    shell when empty), `DELETE /pty/{id}`, `PUT /pty/{id}` `{size:{rows,cols}}`, `GET /pty/shells`.
    Live I/O is a WebSocket: `ws(s)://<host>/pty/{id}/connect?directory=&cursor=` — Basic auth header
    works (or `?auth_token=<base64(user:pass)>`; `POST /pty/{id}/connect-token` issues a 60 s ticket).
    Server→client frames are raw UTF-8; one control frame is `0x00` + JSON `{"cursor":N}` (end of
    replay). Client→server frames are UTF-8 input. The emulator lives in `terminal/`
    (`TerminalEmulator` + `OkHttpPtyTransport`); resize goes over REST, not the socket.
    The grid renders with a bundled **JetBrainsMono Nerd Font Mono**
    (`res/font/jetbrains_mono_nerd_{regular,bold}.ttf`, OFL-1.1) so zsh/powerline prompt glyphs
    render instead of tofu; PTYs are created as **login** interactive shells (`-l`, or no command
    → server default `$SHELL`) so dotfiles/aliases/PATH match the local terminal.
    **Gotcha:** the grid lives in a plain (non-`State`) emulator object, so a `LazyColumn` item
    that only reads it will be *skipped* by Compose and never redraw — read the `frame` State inside
    the item scope (or the list stays blank while data is actually arriving).

## Features done

Incremental SSE part patching · adaptive power-aware polling (3s busy / 15s fg / 60s bg) · todos
panel · agent+model switcher (persisted per server) · slash commands (`/command`) · copy message ·
session status dots · rename session · per-message model/tokens/ctx/$ · session totals · queued
indicator · per-message diff · project search (`/find`, `/find/file`) · offline cache (Room) ·
notifications (foreground service toggle) · mDNS scan + Tailscale remote field · directory/folder picker ·
message pagination (load-older on scroll + pull-to-refresh) · in-app permission prompts
(Allow once / Always / Deny) · image + file attachments from the phone (rendered inline) ·
agent questions in-app (single/multi-select + custom text, answer or dismiss) ·
in-app update check (launch-time prompt → download → install APK) · session actions (fork / revert /
unrevert / delete message, from long-press menus) · share/unshare session links · sessions search +
server switcher · clickable links in chat · file viewer line numbers + jump-to-line + per-line
LazyColumn virtualization · code blocks with copy button · sticky diff headers · scroll-to-bottom FAB ·
relative timestamps · empty states · SSE-liveness battery saving · system/dark/light theme toggle
(`OcTheme.colors`) · markdown tables + ordered/nested lists · summarize session ·
retry failed send + cancel queued · WorkManager notification fallback (FGS 6h cap) ·
home-screen status widget · two-pane layout on wide screens (≥720dp) · shared motion tokens ·
Material 3 pass: full color-role set (surfaceContainer*, inverse, outlineVariant) so M3 components
match the custom palette, Settings screen (Appearance / Notifications / About / Security),
bottom `NavigationBar` on Sessions, `ListItem` rows, Extended FABs, chat overflow menu,
consistent `TopAppBar` colors. Markdown / code blocks / diffs stay custom-rendered ·
**in-app terminal** (full PTY + ANSI/xterm emulator, bottom-nav item).

## Gotchas

- Prism4j 2.0.0 bundles ONLY: markup, clike, css, javascript, java, kotlin, python, go, c, cpp, csharp,
  dart, swift, sql, json, yaml, markdown, git, groovy. Element is `@PrismBundle(include=…)`.
  `org.jetbrains:annotations-java5` is excluded (classpath dupes).
- Room **must be ≥ 2.8** (2.6 can't read Kotlin 2.2 metadata → kapt crash).
- Release APK signs with the real keystore if `keystore.properties` exists (CI: 4 `ANDROID_SIGNING_*`
  secrets); else falls back to the debug key. Debug-key and real-key APKs can't install over each other
  (uninstall once to switch).
- Chat input bar: targetSdk 36 forces edge-to-edge → `imePadding()` on the Scaffold, else keyboard
  hides the input.
- **Kotlin 2.2.20 IR bug:** `runCatching { SomeObject.method() }` (a top-level `object` referenced
  inside an inline lambda, from a class with a companion) crashes the compiler with
  "Backend Internal error ... Parent of this declaration is not a class: CLASS OBJECT [companion]".
  Hoist the object to a local val or use try/catch instead of `runCatching`. (Hit by
  `StatusWidget.update`; see `ChatViewModel.updateWidget`.)
- **Theme:** UI must use `OcTheme.colors.*` (theme-aware palette from
  `ui/theme/Color.kt` + `LocalOcColors`), not the raw `Color.kt` constants, so light mode works.
  `ThemeMode` (system/dark/light) is persisted in `ServerStore` ("theme_mode").
- **Wide screens:** `WideTwoPane` in `MainActivity` (≥720dp) puts the sessions list beside the chat
  and the file list beside the viewer; narrow keeps the old navigate-to-detail routes.
- **Notifications fallback:** Android 15 caps dataSync FGS at ~6h/day. `NotifyScheduler`
  (WorkManager, 15 min) backs up `SessionWatchService` and also refreshes the home-screen
  `StatusWidgetProvider` (state cached in the `opencode_widget` SharedPreferences).
- **Blocking prompts (permission + question) are app-wide**, owned by `AppViewModel.prompts` and
  rendered in `MainActivity` — NOT inside `ChatScreen`. They must show on any screen (and when the
  server is the TUI on another port). Two traps that caused "no sheet ever appears":
  (1) the SSE stream is directory-scoped, so subscribing with a `projectDir()` captured at VM init
  (often still `null`) silently misses every event — always resubscribe on `currentDirectory` change
  (`collectLatest`); (2) `server.heartbeat` arrives even when the stream is NOT delivering for the
  selected instance, so heartbeats must not count as liveness or the poll fallback is suppressed.
- mDNS resolves the **LAN IP**, not Tailscale; use the "Tailscale (remote)" field (`100.x.y.z` or
  `<mac>.ts.net`, https if `.ts.net`).
- **Update check avoids the GitHub REST API** (rate limit): `GET /releases/latest` 302-redirects to
  `/releases/tag/v<ver>` (parse the final URL), and the fixed
  `/releases/latest/download/app-release.apk` always serves the newest asset. Install needs
  `REQUEST_INSTALL_PACKAGES` + a `FileProvider` over `cacheDir/update/` (`res/xml/file_paths.xml`);
  on Android 8+ if `canRequestPackageInstalls()` is false, send the user to
  `ACTION_MANAGE_UNKNOWN_APP_SOURCES`. **Caveat:** the release APK must be signed with the same key as
  the installed app (CI falls back to the debug key) or the install fails. "Later" persists a skipped
  version until a newer one appears.

## Open / next ideas

- Remote `http(s)` image parts (currently only `data:` URLs render inline), image
  thumbnails/compression before upload, incremental refresh for `session.something` events
  (currently full refetch), update check only over Wi-Fi + manual "check now" button, widget for
  multiple sessions, richer wide-screen layout (three panes / list-detail for servers).

## Verification

Validated against opencode **1.18.31** live server: sessions, messages, prompt_async, command, diff,
SSE, file list/content, find/find-file, provider/agent, PATCH title. `make serve` starts a local test
server on :4199 (password `secret`).
