<!-- ship: v0.1.55 (versionCode 56) -->

# PROGRESS — OpenCode Mobile (Android)

**What:** native Kotlin/Compose Android client for an opencode server (Mac). **Code editor that saves
to the Mac (CodeMirror 6)**, sessions, live chat (SSE), files + code viewer, diffs, git commit, search,
offline cache, notifications.

**Repo:** git@github.com:Drzaln/opcd-app.git · **Package:** `dev.opencode.mobile` · single `:app` module.

**Stack (pinned, don't bump blindly):** Kotlin 2.2.20 · AGP 8.13.0 · Gradle 8.13 · compose-bom
2025.09.00 · minSdk 26 · compile/target 36 · Retrofit 2.11 · OkHttp 4.12 · kotlinx.serialization
1.7.3 · Room 2.8.5 · Prism4j 2.0.0 · java-diff-utils 4.12 · DataStore 1.1.1 · JUnit 4.13.2 (tests only).
**Vendored JS (not in Gradle):** xterm.js 5.5.0 (`assets/terminal/`), CodeMirror 6 bundle
(`assets/editor/editor.js`, built by esbuild 0.28.2 from `tools/editor-bundle/`, exact versions in its
`package-lock.json` and `assets/editor/NOTICE.txt`; state 6.7.6 · view 6.43.15 · language 6.13.1).

**Build/ship:** `make build` · `make release` · `make ship` (`scripts/ship.sh` bumps version, builds,
pushes `main`, tags `v*`; CI builds + GitHub Release). Say "ship it" → always run `make ship`
after updating this file.

## Architecture (where things live)

- `OpenCodeApp` — owns `ServerStore` (prefs), `OpenCodeRepository` (HTTP/SSE), `CacheStore` (Room).
- `MainActivity` — NavHost + `Routes` (servers→sessions→chat/files/file/diff, **code**, **editor**).
  Bottom nav = `ui/common/AppBottomBar` (**Code · Sessions · Terminal · Settings**; `AppNavRail` on
  short landscape screens).
- `AppViewModel` — servers list, active server, `currentDirectory`, notifications toggle, `probe()`.
- `data/model/Dtos.kt` — all kotlinx DTOs.
- `data/net/`: `OpenCodeApi` (app-facing compat client — auto-detects 1.x vs 2.0), `OpenCodeV1Api`
  (Retrofit, 1.x routes), `OpenCodeV2Api` (Retrofit, `/api/*` 2.0 routes returning raw `JsonElement`),
  `OpenCodeRepository` (client cache + SSE + reconnect + V2 event mapping), `ServerStore` (DataStore),
  `NsdDiscovery` (mDNS).
- `data/cache/CacheStore.kt` — Room key/json cache (sessions, messages).
- `notify/` — notification channels + `SessionWatchService` (foreground SSE watcher).
- `ui/common/` — `CodeHighlighter` (Prism4j), `Markdown`, `DiffLines`, `CodeBlock`, `shortenPath`,
  `ConnectionIndicator`.
- `ui/{servers,sessions,chat,files,file,diff,settings}/` — screens + their ViewModels.
- `ui/editor/` — the code editor (see "Code editor" below): `EditorViewModel` (activity-scoped per
  server + folder: tabs, save/conflict state, git), `EditorWebView` (CodeMirror WebView + bridge),
  `EditorScreen` (phone route, tabs, key row, dialogs, palette, Changes/Commit), `CodeScreen` (Code tab:
  phone tree / wide IDE layout).
- `data/net/FileWriter.kt` (read + save + `EditorFiles` pure helpers), `PtyOneShot.kt` (one-shot PTY
  command runner shared with `GitRunner`), `GitRunner` (branch, status, diff, commit).

## opencode 2.0 (V2 API) support

The app speaks **both** API generations and picks one per server by probing `/api/info` (2.0 answers
JSON `{version}`; 1.x 404s → fall back to 1.x). All mapping lives in `data/net/OpenCodeApi.kt`, which
exposes the same app-facing methods and returns the existing internal DTOs, so screens/ViewModels are
unchanged. V2 payloads are parsed as raw `JsonElement` (see `OpenCodeV2Api.kt`) and mapped to V1 shapes.

V2 differences that matter (verified against 2.0.26):

- **Base path:** everything moves under `/api/…`; the bare root serves the web UI (HTML), which is
  what produced the old `Unexpected JSON token … had '<'` crash.
- **Instance routing:** sessions/files accept `?directory=<path>`, but `vcs`/`agent`/`provider`/`model`/
  `fs/*`/`pty`/`permission` take a nested `?location[directory]=<path>` (OkHttp leaves the brackets).
- **Envelopes:** most routes return `{location, data}` or `{data, cursor}`; `/api/project` returns a
  bare array.
- **Sessions:** `location.directory`, nested `tokens`, `model{id,providerID}`, `agent` string;
  status is `/api/session/active` (`{data:{<id>:{type:"running"}}}` → mapped to busy/idle).
- **Messages are a different model:** `{type:"user|assistant", content:[{type:"text|reasoning|tool"}]}`
  (no `parts`/`role`). Mapped to the app's `MessageData`/`Part` (tool `state.content[]` joined to
  `output`); reasoning/tool/text render unchanged. User messages carry attachments in
  `files:[{data,mime,source,name?}]`; inline images are rebuilt as `data:<mime>;base64,…` file parts
  so the chat renders them (and `file://` sources keep an openable `source.path`).
  **Quirk:** the endpoint returns **newest-first**
  (`order=desc` by default) — the client reverses each page to ascending and uses `cursor.next` for
  load-older, or the chat renders reversed.
- **Sending:** `/api/session/{id}/prompt` `{text, files:[{uri,name}]}` (no inline model/agent) — the
  client first POSTs `/agent` and `/model`; commands go to `/api/session/{id}/command` `{name,text}`;
  abort is `/interrupt`. Model payloads are `Model.Ref` `{id, providerID, variant?}` — reasoning
  variants (low/medium/high/xhigh/max) come from `/api/model` → `variants[].id` and apply to both
  prompt and command turns.
- **Files:** `/api/fs/list` `{path,type}` (dirs have a trailing `/`), `/api/fs/read/<path>` returns
  raw bytes (content sniffed for binary), `/api/fs/find?type=file|directory`. **No content grep** →
  the Content search tab returns nothing.
- **Diff:** `/api/session/{id}/diff` returns a unified-diff `patch` per file (not full before/after) →
  `DiffLines.parsePatch` renders it.
- **PTY:** create/list/delete under `/api/pty`; websocket `/api/pty/{id}/connect?location[directory]=&
  cursor=&ticket=`, ticket from `POST /api/pty/{id}/connect-token`; shells from `/api/config/shell`.
- **Prompts:** permissions are `/api/permission/request` + `/api/session/{id}/permission/{requestID}/reply`
  `{decision}`; agent **questions are now "forms"** (`/api/form`, reply `/api/session/{id}/form/{formID}/
  reply {answer:{<key>:value}}`) — mapped back onto the existing question UI (options carry their
  `value`, fields their `key`).
- **Events:** `data: {"id","type","data"}` with types like `session.text.delta`, `session.step.*`,
  `session.tool.*`, `session.execution.*`, `session.usage.updated`, `permission.asked`, `form.created`.
  `OpenCodeRepository` maps these onto the V1 event names the UI already understands: text/reasoning
  deltas, tool calls, steps and the user's message → live `message.updated` / `message.part.updated`
  patches (`V2LiveEvents`); `execution.started/succeeded` → status busy/idle; forms/permissions →
  prompt shapes.
- **Removed in 2.0 (degraded):** todos (panel empty), content grep, session share/unshare links.
- **Robustness:** the API client buffers GET bodies and retries once on `IOException` (e.g. a
  mid-stream cut surfaced as `unexpected end of stream`) — SSE and `/fs/read/` are excluded so
  streams stay streaming and large file reads aren't buffered.

## Server integration (hard-won facts)

1. **Instance routing:** pass `?directory=<abs path>` on nearly every call (sessions, messages,
   prompt_async, command, diff, abort, todos, file list/content, status, SSE). Global: `/global/health`,
   `/project`. The app keeps `AppViewModel.currentDirectory` (folder picker on Sessions screen — a
   tappable card that opens a scrollable bottom sheet; filter box when >6 projects, active folder
   check-marked, "Server default" + custom path; `switchDirectory()` clears stale sessions on change).
2. **SSE:** event type is inside the JSON body — `data: {"type":"...","properties":{...}}` — NOT the SSE
   `event:` field. `/event` also takes `?directory=`.
3. **Prompt body:** must include the part discriminator. Json config is
   `ignoreUnknownKeys=true; explicitNulls=false; encodeDefaults=true` (the last two are required —
   `type` has a default and would otherwise be omitted → HTTP 400 "Expected { readonly type ... }").
   Body: `{"parts":[{"type":"text","text":"..."}]}`.
4. **Session diff:** `/session/{id}/diff[?messageID=]` returns `FileDiff{file,before,after,additions,
   deletions}` where `before`/`after` are FULL file contents → compute line diffs client-side
   (`ui/common/DiffLines.kt`). `messageID` must be a **user** message (server requires `role==="user"`).
5. **Realtime both ways:** the Mac must expose a shared server the TUI and app both attach to.
   **1.x:** run the TUI on a fixed port (`OPENCODE_SERVER_PASSWORD=… opencode --hostname <ip> --port 4096`).
   **2.0:** configure the background service once (`opencode service set hostname/port/password` then
   `opencode service start`) and run plain `opencode` — it attaches to that service. A separate
   `opencode serve` is a different process/event-bus → no live TUI↔app sync. (Linux box: see `README.linux.md`.)
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
    **Rendering:** the canvas viewport is measured by the `BoxWithConstraints` that wraps *only* the
    canvas (not the whole column) so `rows`/`cols` match the visible area — measuring the full column
    over-reported `rows` by the key/status rows and pushed the prompt below the canvas. Output is
    scrolled with a plain `verticalScroll` + a tall `Canvas` (native fling, no custom drag detector),
    and a `RowLayoutCache` skips re-building/re-measuring rows whose cells didn't change (the
    per-frame `AnnotatedString` rebuild was the main jank source).
16b. **Two terminal renderers** (top-bar ⋮ → Renderer, persisted as `ServerStore.terminalRenderer`,
    default `xterm`):
    - **`native`** — the Compose `Canvas` + `TerminalEmulator` described in 15. Compact ANSI/xterm
      subset: no DEC graphics charset, no mouse reporting, no device-status replies, no hardware-key
      handling (soft keyboard only), 2000-line scrollback.
    - **`xterm`** — `ui/terminal/XtermTerminalView.kt`: a WebView running vendored **xterm.js 5.5.0**
      (`app/src/main/assets/terminal/{xterm.js,xterm.css,addon-fit.js}`, MIT, see
      `assets/terminal/NOTICE.txt`). Full xterm fidelity: reflow, mouse, DEC charset, bracketed
      paste, IME, hardware keys, 8000-line scrollback + search. Kotlin still owns the WebSocket
      (auth + 1.x/2.x URL split stay in one place); the page is fed the raw PTY stream and calls back
      for input/resize.
      **The engine is inlined, not fetched:** `readSeed()` splices `xterm.js`/`xterm.css`/`addon-fit.js`
      into `terminal.html`'s `<link>`/`<script src>` tags before loading. Loading them as subresources
      depended on `shouldInterceptRequest` serving a fake `https://terminal.local/` origin — if any of
      that misfires the engine never loads and the terminal is silently blank. Inlining keeps the
      critical path free of subresources; the only intercepted request left is the **optional font**,
      served from `res/font` via `openRawResource` (no second 2.4 MB copy in assets; `file://` can't
      reach `res/` on targetSdk 30+). A font failure is logged, not fatal — xterm falls back to
      system monospace. `/favicon.ico` is answered with a silent 204 (it is a normal browser request,
      not an error).
      **★ Gotcha — load the page only when the view has a size.** `loadDataWithBaseURL` used to run in
      the WebView factory, i.e. while the view was still **0×0** (Compose creates it during measure).
      The page then established a **0-height layout viewport**: `window.innerHeight` was 667 but
      `getComputedStyle(documentElement).height` was `0px`, so `height: 100%` resolved to 0, `#term`
      collapsed to its 10 px padding, `fit()` produced **1 row**, and the shell's whole output was
      painted into a 10 px strip at the top-left — and it never recovered however the view was resized.
      Fix: load via `addOnLayoutChangeListener` on the first non-zero layout (+ immediate load if
      already sized, guarded so it loads once). Belt-and-braces: `#term` is `position: fixed` (sized
      from the visual viewport, which stays correct even when the document box collapses).
      **Version gate:** the 5.5 bundle uses ES2020 (`?.`, `??`) so it needs **Chromium 80+** —
      the engine is a *parse* error below that. `MIN_WEBVIEW_CHROME` + `webViewChromeMajor()` (parsed
      from the default user agent) make `TerminalScreen` fall back to the native canvas with an
      explanatory banner instead.
      **Rendering:** `lineHeight: 1.34` (the native renderer's 16sp/12sp ratio) so Nerd Font powerline
      glyphs don't overlap neighbouring rows, plus `term.refresh()` after a fit to clear stale cells.
      Diagnostics (verified on-device, Android 16 / Chromium 154): `console.*` is forwarded by
      `WebChromeClient.onConsoleMessage` to logcat tag **`XtermTerminal`** — `OC start host=… cols=…
      rows=…` and `OC first-write chars=… rows=…` are the two lines that pinpoint geometry vs. stream
      problems. Plus `window.onerror`/`onunhandledrejection`, `onReceivedError/HttpError` (main frame),
      a 7 s "never reported ready" watchdog, and a red banner over the terminal with a **Use native
      renderer** button. The bottom bar shows `xterm ready · 50×34 · no output received` when the
      engine started but nothing has been streamed yet.
    **Renderer plumbing:** `TerminalViewModel.setRawMode(raw)` flips the output sink and re-attaches
    (full replay, so the newly selected renderer starts clean); in raw mode bytes go to a bounded
    `Channel<String>` (`rawOutput`) instead of the emulator, so a WebView that is still loading simply
    drains the backlog when it reports ready (`consumeRawDropped()` → `reattach()` if it overflowed).
    `attachGeneration` is bumped per attach; the WebView prepends `__ocReset()` to the first chunk of a
    new generation so a reconnect doesn't duplicate the replay. Output is coalesced to one
    `evaluateJavascript` per 16 ms (base64 → `atob` → `TextDecoder`), because a build log arrives as
    thousands of tiny chunks.
    `Ansi16` and `KeyRow` are `internal` so both renderers share them (`KeyRow(showCtrl = false)` in
    xterm mode — xterm handles ctrl itself).
    **Threading:** PTY parsing runs on `Dispatchers.Default.limitedParallelism(1)` in the ViewModel;
    `TerminalEmulator` guards every mutation/read on an internal lock (`write`/`resize`/`reset` are
    `synchronized`, the UI draws inside `readLocked { … }`), so a background writer can't mutate rows
    mid-frame. `attach()` cancels + joins the previous socket before `reset()`.
    **Resize (keyboard):** shrinking/growing the grid scrolls rows to/from scrollback (like a real
    terminal's SIGWINCH) instead of dropping the bottom rows, so opening the keyboard no longer loses
    the prompt and `totalLines` stays constant (the viewport doesn't jump).
    **Gotcha:** the grid lives in a plain (non-`State`) emulator object, so a `LazyColumn` item
     that only reads it will be *skipped* by Compose and never redraw — read the `frame` State inside
     the item scope (or the list stays blank while data is actually arriving). The terminal canvas does
     this in its draw lambda (`@Suppress("UNUSED_EXPRESSION") frameState.value`).
16. **OpenCode Go plan usage (Settings → OpenCode Go):** `GET https://opencode.ai/zen/go/v1/usage`
    with `Authorization: Bearer <go-api-key>` returns
    `{"usage":{"rolling":{status,percent,resetsAt},"weekly":{…},"monthly":{…}}}` — 5h/weekly/monthly
    percentages. This is the **provider endpoint on opencode.ai**, NOT the local server (which has
    no usage route and never exposes credentials), so the app stores the Go key itself
    (`ServerStore` `opencode_go_api_key`, from the user's Zen console) and calls it directly via
    `data/net/GoUsageClient.kt`. Poll-based (no SSE): refresh on open/save/resume + every 60 s while
    the Settings screen is visible. Aggregate only — no per-model breakdown / balance.
17. **Git branch:** current branch is `GET /vcs?directory=` → `{branch, default_branch}` (both
    `null` outside a repo → hide the UI). The server exposes **no** branch-mutating route
    (`/vcs` is GET-only; POST falls through to the web SPA), so switching/creating runs git through
    a one-shot PTY: `POST /pty` `{command:"sh",args:["-c","<git cmd>; printf '__OC_EXIT__%s\n' $?"],
    cwd,title:"git"}` → connect `ws /pty/{id}/connect` → collect stdout/stderr until close → parse
    exit code. `data/net/GitRunner.kt` wraps this (ANSI-stripped, timeout, deletes the pty).
    Branch names are validated (`^[A-Za-z0-9/_.-]+$`, no leading `-`, no `..`) before interpolation.
    Use `git --no-pager -c color.ui=false` — a TTY otherwise starts a pager and emits terminal
    control sequences.
    **Gotcha:** the server reaps a PTY the instant its command exits, so a fast git command is gone
    before the WebSocket handshake finishes (connect 404 → OkHttp `Expected HTTP 101 response but
    was '404 Not Found'`). Append `sleep 60` after the exit marker and stop reading as soon as the
    marker matches (don't wait for socket close).

18. **Writing files (the editor's save path).** Checked both generations (Nov 2026):
    - **opencode 1.x:** no write route — `/file`, `/file/content`, `/file/status` are all GET.
      `/file/content` also returns *decoded* text, not the bytes on disk, so 1.x **reads** through the
      PTY too (exact bytes, base64 between markers).
    - **opencode 2.0.x / 2.1.x:** only `fs/list`, `fs/read`, `fs/find`. `/api/fs/read/<path>` returns
      exact bytes (used for reads + hashing on all 2.x).
    - **opencode 2.2+ (verified 2.2.6):** `POST /api/experimental/fs/write?location[directory]=&path=`
      with the raw body (`application/octet-stream`) → `{location, data:{path}}`. Advertised in
      `/openapi.json` as operationId `experimental.fs.write`, which is how `OpenCodeApi.supportsFileWrite()`
      detects it. **Caveats:** it writes **in place** (same inode — not atomic), creates parent dirs, and
      is **not confined to the location** (`path=../x` happily wrote outside the project), so the
      client must validate paths itself (`EditorFiles.resolve`). Experimental = may change.
    - **PTY writer (1.x, 2.0, 2.1):** `sh -c` script (`EditorFiles.writeScript`): `stty -echo`; refuse
      symlinks/non-files; re-hash the target and compare with the hash the editor opened (conflict →
      `__OC_CONFLICT__<hash>`, exit 9, nothing written); print `__OC_READY__`; `base64 -d > "$T"` where
      `$T` is a hidden temp file **in the same directory**; copy the mode (`stat -c %a` then BSD
      `stat -f %Lp`); `mv -f "$T" "$F"`; print `__OC_HASH__<sha256>`. The phone waits for READY, streams
      base64 in **76-char lines** (canonical-mode TTY lines max out at MAX_CANON = 1024 on macOS),
      64 lines per WebSocket frame, then `\x04` (EOF). `trap` removes the temp file on exit/HUP.
      Measured locally: 2 MB in ~0.4 s, no echo, exec bit preserved, never truncates the target.
    - **Paths:** passed as single-quoted words prefixed `./` (so a leading `-` can't be an option),
      never interpolated raw. Rejected before any request: `..` segments, absolute paths outside the
      project folder, `~`, NUL, CR/LF (`EditorFiles.resolve`, unit-tested).
    - **Verification:** success is reported only when the on-disk SHA-256 (script output, or a re-read
      on the native route) equals the phone's hash of the bytes it sent. Limits: 2 MB, UTF-8 text only
      (NUL byte or invalid UTF-8 = binary → read-only).
    - **Quirks found on the way:** `POST /api/pty/{id}/connect-token` answers `ForbiddenError "Invalid PTY
      connect token request"` on 2.2.6 (the app already falls back to Basic auth on the socket); the
      server appends `-l` to PTY args, which harmlessly becomes `$0` of `sh -c`. Every directory you hit
      with `?location[directory]=` is remembered as a **project** by the server.

## Code editor (Phase 1)

- **Engine:** CodeMirror 6 in a WebView, same recipe as the xterm terminal: `editor.js` inlined into
  `editor.html` (`readEditorSeed`), font served from `res/font` via `shouldInterceptRequest`
  (`https://editor.local/font`), page loaded on the first non-zero layout, `console.*` → logcat tag
  **`CodeEditor`**, Chromium 80+ gate (`MIN_WEBVIEW_CHROME`) else a banner + the read-only viewer.
- **Bridge:** Kotlin → JS `__ocLoad(path, b64, lang, line[, savedB64])`, `__ocShow`, `__ocClose`,
  `__ocGetText(id, path)`, `__ocSaved(id, path)`, `__ocSetTheme(json)`, `__ocSetFontSize(px)`,
  `__ocSetWrap`, `__ocInsert(b64)`, `__ocCommand(name)` (undo, redo, insertTab, indentMore/Less,
  toggleComment, find, goToLine, left, right, selectAll). JS → Kotlin (`AndroidEditor`): `onReady`,
  `onChange(path, dirty, hash)`, `onSaveRequested`, `onCursor(line, col)`, `onCommand("palette"|
  "closeTab")`, `onText(id, path, text)`, `onSnapshot(path, text)`, `onFocus`, `onError`. Text goes
  Kotlin → JS base64 (like the terminal); JS → Kotlin as a plain string.
- **State lives in `EditorViewModel`**, not the WebView: per tab the on-disk text + hash and the latest
  unsaved snapshot (the page posts one 1.5 s after edits). Whenever the WebView is recreated
  (navigating away, layout switch) `onEditorReady()` drops queued commands and replays every tab.
  Undo history is per tab (one `EditorState` per tab in JS) and is lost only when the page is rebuilt.
- **Save flow:** `save()` → `GetText(id)` → `onText` → `FileWriter.save(path, text, baseHash)` →
  `Saved(hash)` (page marks that exact doc as saved, so typing during a save stays dirty) |
  `Conflict(remote)` → dialog **Reload / Overwrite / Show diff** (`DiffLines.compute`) | `Failed(reason)`.
- **Line endings:** CRLF files keep CRLF (`EditorState.lineSeparator` + `sliceDoc()`); indent unit is
  detected per file. Ligatures of the Nerd Font are disabled in the editor.
- **Theme:** colors come from `OcTheme.colors` as CSS variables, so light/dark switches live. New roles
  added for the mockups: `textBody`, `lineNumber`, `accent`, `accentSoft`, `link`, `warning`,
  `errorText`/`errorBg`, `editorLine` (selection), `editorActiveLine`, `popup`/`popupBorder`, `syntax.*`.
  `textSecondary` stays the *muted* role (mockup "text muted" #9AA4B2); mockup "text secondary"
  #C9D1D9 is `textBody`. Bg/border moved to #0E1116 / #262C36; `errorContainer` = #3A1D20 / #FFB4AE.
- **Navigation:** Code tab (`Routes.CODE`) = tree (+ Recent) on phones, IDE layout at ≥ 720 dp
  (tree 240 dp │ editor │ Terminal/Git panel 190 dp; 300 dp right slot at ≥ 1000 dp; nav rail instead
  of the bottom bar when height < 600 dp). `Routes.EDITOR` (`path`, `line`) is the phone editor; on wide
  screens it renders the IDE layout and opens the file in place. Chat file links and diff file
  headers/lines open the editor at that line. The old viewer stays at `Routes.FILE` (long-press →
  View read-only).
- **Git:** `/vcs` for the branch + `git status --porcelain` (PTY) for "N changed"; Changes list → per-file
  `git diff HEAD` (untracked: `--no-index /dev/null`) rendered with `DiffLines.parsePatch`; Commit… runs
  `git add -A && git commit -q -m '<quoted>'`. No push.

## Phase 2 — optional Mac helper (slices 1–4 built: pairing + TypeScript + format on save, Metro, adb, device screen + emulators; then iOS simulators slice 1)

**Built (branch `feat/mac-helper`):** `helper/` (Node/TS, esbuild → `dist/helper.mjs`, `npm start`,
`npm test`; see `helper/README.md` for setup, security and the wire protocol).
- **Pairing:** `/helper/pair/start` → the phone shows the 4-digit code, the Mac shows an osascript
  Allow/Deny dialog with the same code (terminal `y/N` fallback); `/helper/pair/wait` → bearer token,
  stored hashed in `~/.config/opencode-mobile-helper/devices.json`. App side: `HelperManager`
  (`data/net/HelperClient.kt`, owned by `OpenCodeApp`, follows the active server from `AppViewModel`),
  pairing per server in `ServerStore.helperPairing`, **Settings → Mac helper** (status, Pair with the
  code dialog, Unpair, Format on save). The helper is looked up at the opencode server's host, :4097.
- **TypeScript:** `@codemirror/lsp-client` 6.3.1 in the editor bundle; JS ↔ `AndroidEditor.onLsp` ↔
  `EditorViewModel.lspSend` ↔ helper `lsp.send` ↔ `typescript-language-server --stdio`, and back via
  `lsp.message` notifications → `__ocLspRecv`. A session starts when the helper is Ready, the page is
  up and a TS/JS tab is open; every page (re)load gets a fresh session (a server can't be initialized
  twice). Completions, hover, signature help, diagnostics (gutter + squiggles, ⋮ → Problems), go to
  definition (opens the target file as a tab), find references, format document, **project-wide
  rename** (our own: lsp-client's rename only edits files on screen, which with one shared view would
  half-rename a project — `__ocRenameTo` opens every file in the WorkspaceEdit as a tab and edits it;
  nothing is saved automatically). Status shows "TypeScript · LSP ready".
- **Format on save:** before writing, `format {root, path, text}` (Prettier with the project's config,
  then `eslint --fix` if the project has ESLint); the editor applies the result as one minimal edit
  (`__ocReplace`, cursor kept on its line) and marks it saved.
- **Gotchas:** tab switches must always go through Kotlin (`onOpenFile` → `vm.open`/`select`), never
  `__ocShow` from JS — otherwise the header/path bar and the visible doc disagree. Renaming at an
  *imported* name gives a local alias (`A as B`), like VS Code. Without a `tsconfig.json`, tsserver only
  knows open files and their imports, so cross-file rename/references need a tsconfig (normal projects
  have one). `typescript@7` (native) has no `tsserver.js`; the helper pins 5.9.3 as its fallback.
- **Verified** on the emulator (helper on 127.0.0.1 with the loopback-only auto-approve): pair →
  Connected; LSP ready on `Signup.tsx`; diagnostics; completions popup (`toUpperCase`); format on save
  (Mac file = Prettier output, tab clean); go to definition into an unopened file; rename at the
  definition updated both files, saved, verified on disk. **Physical device** (vivo I2219 over Tailscale
on 4G, real opencode service on :4096, helper on the Mac's Tailscale IP): found → paired via the real
macOS Allow dialog → Connected; LSP ready on `Signup.tsx`; completions (`trim…`); format on save
(Prettier output on disk). A save with formatting took ~10 s over 4G (format + write + verify are
separate round trips).

**Slice 2 — Metro (`tuc.3`, commit `070c734`):**
- **Helper** (`helper/src/metro.ts`): `MetroManager`, one Metro per project root, owned by the helper
  (not the socket) so a 4G drop doesn't kill the bundler. `metro.status|start|stop|reload`;
  `metro.log` / `metro.status` notifications go to every connected phone. Detection from
  `package.json` (Expo → `expo start`, unless the start script is `react-native start`; else
  `react-native start`; otherwise refused), run from `node_modules/.bin` (else `npx --no-install`) in
  its own process group with `FORCE_COLOR=0` (not `NO_COLOR` too — Node then warns on every worker; not
  `CI` — Expo disables reloads). Ready = `GET /status` → `packager-status:running`; a port already
  serving Metro is reported as `external`. 2000-line ring buffer; ANSI stripped; CR-repainted bundle
  progress collapsed into one entry; info/warn/error classification, code-frame lines take the level
  of the error above (`frame`, not counted). Reload = `POST /reload` (fallback `r` on stdin). Stop =
  SIGTERM to the group, SIGKILL after 4 s; requested stops report `stopped`, crashes `exited`. The
  helper stops all Metros on SIGINT/SIGTERM/SIGHUP.
- **App** (`ui/editor/Metro.kt`): `MetroController` (on `EditorViewModel.metro`) mirrors the helper —
  `metro.status` on connect / panel open (state + last 500 lines), notifications after; a new run
  (`starting`) clears the old log. `MetroPanel`: status dot + command, Start / Reload / Stop, All ·
  Problems filter, Clear, log rows (errors on `errorBg`, warnings in `warning`), follows the tail until
  you scroll up. Wide Code layout: a **Metro** tab in the bottom panel (green dot while running, red
  count of unseen errors). Phones: a **Metro** app-bar button on the Code tab and in the editor route
  (same dot/badge) opening `MetroSheet`. All of it only while the helper is Ready and advertises
  `metro`.
- **Fixed on the way (slice 1 bug):** `HelperManager` launched one coroutine per incoming message, so
  notifications could be reordered (Metro code frames came out shuffled; LSP traffic was exposed to
  the same race). Now one unlimited channel + one forwarder.
- **Verified:** `npm test` (4 tests; the new one drives detect → start → running → error line →
  catch-up → reload → stop against a stand-in `react-native start`). Real Expo SDK (blank-typescript
  template, scratch folder) through a loopback helper: start → running, `GET /status`, a broken
  import → "Android Bundling failed" + "Unable to resolve" as errors with the code frame attached,
  reload via HTTP, stop closes the port. Emulator (Medium_Phone, `make serve`, loopback helper,
  stand-in Metro in the scratch project): non-RN folder → "Not a React Native or Expo project",
  Start → Running with highlighted warn/error, log survives an app reinstall (catch-up), Reload,
  Stop → port free, unseen-error badge on the app-bar icon, wide layout Metro tab. **Physical phone**
  (vivo I2219, release build, real helper on the Tailscale IP, user's opencode service): the scratch
  Expo project detected as `npx expo start`; Start → Running; a broken import + bundle request →
  "Android Bundling failed" / "Unable to resolve" in red with the code frame, Problems (2); Reload;
  Stop → port free. Found there: the phone sheet opened half-expanded, so the followed tail sat below
  the screen — `MetroSheet` now skips the partial state. Not tried: a real RN app on a device
  fetching the bundle (needs adb — next slice).

**Slice 3 — adb: devices, build & run, logcat (`tuc.4`, commit `80933de`):**
- **Helper** (`helper/src/adb.ts`): adb found via `OC_HELPER_ADB` (tests), `ANDROID_HOME` /
  `ANDROID_SDK_ROOT`, `~/Library/Android/sdk`, Homebrew, PATH; `/helper/info` adds the `adb` feature
  only when it exists. `adb.devices` (parsed `devices -l`: serial, state, model, usb/wifi/emulator),
  `adb.pair` (6-digit code, Android 11+ wireless debugging), `adb.connect`, `adb.disconnect`,
  `adb.reverse`; hosts/ports/serials validated (no shell, `execFile`). **Build & run** (`run.*`,
  helper-owned per project root like Metro, `run.log` / `run.status` to every phone): Expo →
  `expo run:android --device <serial> --no-bundler`, React Native → `react-native run-android
  --deviceId <serial> --no-packager`, else `./gradlew installDebug` (root or `android/`); always
  `ANDROID_SERIAL`, `ANDROID_HOME` filled in when a launchd helper lacks it, `adb reverse tcp:8081`
  first so the app reaches the helper's Metro. Gradle's stderr isn't treated as red; only real
  failures are. **Logcat** (`logcat.start|stop`, owned by the connection — a view, not a job): modes
  App JS (`ReactNativeJS ReactNative AndroidRuntime:E Expo`), Errors (`*:E`), All (`*:I`); a filtered
  backlog from `logcat -d` first (`-T N` counts lines *before* the tag filter, so a quiet app's JS lines
  fell out of it), then `-T 1` live; batched every 250 ms, max 300 lines per batch, the rest counted as
  skipped.
- **Metro reload fixed** (slice 2 bug found here): Expo's dev server has no `POST /reload` — it answered
  with a manifest (200), so Reload "succeeded" and did nothing. Reload now broadcasts
  `{version: 2, method: "reload"}` on the dev server's `/message` socket (what Metro's `r` key does;
  Expo and the RN CLI both have it), then falls back to `/reload` (non-JSON reply only) and `r` on stdin.
- **App** (`ui/editor/Android.kt`): `AndroidController` (on `EditorViewModel.android`) — devices,
  selection (first ready device by default; kept while it's still listed), Run/Stop mirrored like
  Metro (a new run clears the log), logcat stream opened only while the Logcat tab is on screen
  (restarted on device/mode change; lines that arrive before `logcat.start` replies with the id are
  held and merged — the backlog races the reply), lines shown as `HH:mm:ss L Tag: msg`, text filter,
  Clear, skipped-lines note. **Connect…** dialog: host prefilled with this phone's Tailscale IPv4
  (100.64/10 from `NetworkInterface`), port 5555, optional pairing port + code → `adb pair` then
  `adb connect`. Wide layout: **Android** tab in the bottom panel (dot while building, red count of
  unseen errors); phones: an **Android** app-bar button (Code tab + editor) opening `AndroidSheet`.
  Shared `LogLineRow` / `FilterChipText` with Metro.
- **Verified:** `npm test` (5 tests; the new one runs a fake adb + fake gradlew: device parsing,
  connect ok/refused/bad host, pair code check, Gradle build with `ANDROID_SERIAL` and no false red
  lines, logcat backlog first then live info/warn/error). App unit test `AndroidLogcatTest`. Emulator
  (Medium_Phone, loopback helper, real adb; the user's phone was also attached over USB, so the
  emulator was picked explicitly — nothing was installed on the phone): in the scratch Expo project,
  Metro Start, Android → Emulator → **Run** = real `expo run:android` (prebuild + Gradle, BUILD
  SUCCESSFUL in 40 s, warnings orange, no red) → installed and launched on the emulator, bundle served
  by the helper's Metro through `adb reverse`; Metro **Reload** re-ran the app (logs + LogBox toasts);
  **Logcat** App JS showed the app's `console.log/warn/error` as info/warn/error; Errors mode streams
  live. Known: a reload only reaches an app that's in the foreground (Android pauses a background RN
  app), so with app and editor on the same phone, switch to the app, then it reloads on its next
  Fast Refresh / Reload. **Physical phone** (vivo I2219, release
  build, real helper over Tailscale, phone also on USB to the Mac): the Android sheet listed
  "I2219 · USB"; **Run** in the scratch Expo project built and installed the app on the phone itself
  (Google Play Protect asked to scan the unknown app — the user answered it on the phone; expect that
  prompt on first install of a debug build), launched it with its bundle from the helper's Metro over
  `adb reverse`; a reload brought its `console.log/warn/error` into **Logcat → App JS** in OpenCode on
  the same phone (the filtered backlog took ~2 s on the vivo). Test app uninstalled and the reverse
  removed afterwards. Not tried: wireless pairing (Android's wireless debugging needs the phone on
  Wi-Fi), the vivo's floating assistant bubble can cover the sheet's Run button (tap just above it).

**Slice 4 — live device screen + emulators on the Mac (`tuc.5`, branch `feat/mac-helper`):**
- **Screen, helper** (`helper/src/screen.ts`): scrcpy-server from the user's Homebrew `scrcpy`
  (`SCRCPY_SERVER_PATH` or `/opt/homebrew/share/scrcpy/scrcpy-server`; version from `scrcpy
  --version`, the server insists on an exact match) — chosen over the emulator's gRPC because it works
  for emulators *and* phones and needs no npm dependency. `screen.start {serial, maxSize 800,
  bitRate 1.5 Mbps, maxFps 30}` pushes the jar, `adb forward`s a random `scrcpy_<scid>` socket, starts
  `app_process … Server <ver> tunnel_forward=true audio=false control=true video_codec=h264`, connects
  video then control (the server sends its header only after **both** are connected), reads the
  device name, codec id and the first session packet, then relays H.264 packets as binary WebSocket
  frames `[kind:1][pts:8][data]` (1 config, 2 frame, 3 key frame). **scrcpy 5 stream format** (found
  by probing — differs from 3.x): after the codec id, a 12-byte *session* packet (bit 63 set:
  `[flags][width][height]`, again on every rotation), and media packets use bit 62 = config, bit 61 =
  key frame. Touch (`INJECT_TOUCH_EVENT`, 32 bytes) and keys (`INJECT_KEYCODE` back/home/recents/menu)
  go back on the control socket, coordinates scaled from the app's view to the current session size.
  Back-pressure: if the phone's socket has > 2 MB buffered, reading pauses (frames aren't dropped — a
  dropped H.264 frame smears until the next key frame). One screen per connection; `stop()` landing
  mid-`start()` aborts at every step (a stopped session once went on to spawn an orphan server).
- **Screen, app** (`ui/editor/Screen.kt`): `HelperManager.binaryListener` (OkHttp reader thread, in
  order) → `H264Decoder` (MediaCodec `video/avc` on the SurfaceView's surface, low-latency on API 30+,
  rebuilt when a new config packet arrives after rotation; output-format crop → aspect ratio). The
  stream runs while the surface exists and a device is picked; touches are taken in the *Initial*
  pointer pass (otherwise the bottom sheet turns a swipe into a sheet drag); toolbar Back · Home ·
  Recents · Dev menu · Stop. Shown as a **Screen** tab in the Android panel/sheet, and in the wide
  layout's reserved right column (≥ 1000 dp). A USB/Wi-Fi device whose model matches `Build.MODEL` is
  "the phone you're holding": the stream doesn't auto-start there.
- **Emulators** (`helper/src/emu.ts`, asked for mid-slice: *"select emulator with configurable
  avdslim"*): `emu.list` (AVDs from `emulator -list-avds` + which serial each runs as, via `adb emu avd
  name`; whether `avdslim` is installed), `emu.start {avd, headless, cold, ram, slim}` → `avdslim start
  <avd> --ram=… [--headless] [--cold] [--no-slim]` (always naming the AVD — without one avdslim picks
  the first; with no avdslim: `emulator -avd … -memory … -no-audio -no-boot-anim [-no-window]
  [-no-snapshot-load]`), boot confirmed through `sys.boot_completed` (avdslim `--no-slim` exits right
  after spawning), `emu.stop` → `avdslim stop` with stdin closed (it asks "save a Golden Snapshot?
  [y/N]"; with stdin open it waited forever) and success only once adb no longer lists the serial.
  Emulator commands get the account's real home/`ANDROID_AVD_HOME`, whatever HOME the helper runs
  with. App: the device dropdown lists "Emulators on the Mac · avdslim" — **Start <AVD>…** (dialog:
  Headless, Cold boot, Slim after boot, RAM; remembered for the session) or **Stop <AVD>**; a booted
  emulator is selected automatically.
- **Verified:** `npm test` (8 tests: scrcpy control-message layout, the scrcpy 5 packet parser incl.
  session packets across chunk boundaries, emu list/start/stop with a fake emulator + fake avdslim, plus
  everything from earlier slices). Real scrcpy 5.0.1 against a headless emulator: 358×800 session,
  config + key frame + frames, Home key injected; repeated start/stop and a client dropping mid-start
  leave no server, forward or `app_process` behind. Emulator (Medium_Phone running the app, loopback
  helper): dropdown listed the three AVDs; **Start Redmi_Note_13_pro** through avdslim (headless, slim
  off for the test) → emulator-5556 → **Screen** showed its live home screen; a tap opened Chrome on it,
  a swipe opened its app drawer (after the Initial-pass fix), Home/Stop worked; closing the sheet
  stopped the stream; `emu.start`/`emu.stop` round trip (boot ~6 s from snapshot, stop 2.5 s, gone from
  adb). Fixed on the way: decoder thread crashed on `InterruptedException` at stop.
- **Incidents (test environment):** `avdslim start --help` doesn't print help — it launched
  Medium_Phone and began slimming it; undone with `avdslim restore emulator-5554` (50 packages and the
  changed settings restored). Afterwards the emulator showed Google's setup "Sign in with ease" screen
  once (left untouched, pressed Home).
- **Physical phone** (vivo I2219, release build, real helper over Tailscale): dropdown → **Start
  Medium_Phone** (headless, slim off) through avdslim → "Medium_Phone is up" after ~9 s and selected;
  **Screen** streamed the headless emulator to the phone over Tailscale; a swipe opened its app drawer,
  Home worked; **Stop Medium_Phone** from the phone shut it down. Bugs found there and fixed: tapping
  the stream's Stop left the scrcpy server running — `onDispose` read the `wanted` state delegate at
  dispose time (already false) and skipped `screen.stop`; now the flag is captured when the effect
  starts. Hardening from the same hunt: the app always sends `screen.stop`; the helper keeps one
  session per device across connections (a new start replaces an orphan) and pings every WebSocket
  every 20 s, terminating dead ones (a half-open Tailscale socket can't keep a stream alive); session
  start/stop are logged with the server's pid. After the fix: live 1 server → Stop 0 → closing the
  sheet 0.
- **Not verified:** the ≥ 1000 dp right-column placement on a real tablet (same composable as the tab;
  no tablet-width AVD here), rotation of the mirrored device, streaming over 4G (tested over Wi-Fi +
  Tailscale), a phone as the mirrored device, avdslim's slimming path through the app (only `--no-slim` was run).

**Phase 2 is complete** (all five `tuc` slices). Design notes below are kept for reference.

### iOS simulators — slice 1 (`opencode-android-app-scg`)

Asked after Phase 2: "is it possible to have iOS simulator with simslim like the android emulator?"
Slice 1 = everything except the live screen.
- **Helper `sim.ts`:** `findXcrun` (`xcrun --find simctl`, cached 60 s; `OC_HELPER_XCRUN` for tests) →
  feature `ios` (advertised even without adb). `listSims` = `simctl list devices available -j`, iOS
  runtimes only, booted first, newest runtime first; `simslim list --json --booted` adds `slim`
  (`managedDisabled > 0`) and `memory` — simslim can only read the state of a *running* simulator, so
  `slim` is null while it's off. `SimulatorManager.start`: slim → `simslim on <udid>` (reconfigures while
  off, then boots — one step, ~17 s first time); stock → `simslim off <udid>` (also boots) then
  `simslim boot` (no-op "already booted"); no simslim → `simctl boot` + `bootstatus -b`; `window` →
  `open -a Simulator --args -CurrentDeviceUDID`. `stop` → `simslim shutdown` / `simctl shutdown`,
  verified against the list. Notifications `sim.log` / `sim.status`.
- **A UDID is a "serial":** `run.start` with a UDID → `expo run:ios --device <udid> --no-bundler` /
  `react-native run-ios --udid <udid> --no-packager` (no `adb reverse`, no `ANDROID_SERIAL`, `LANG`
  defaulted to UTF-8 for CocoaPods); plain gradle/Xcode projects → "needs an Expo or React Native
  project". `run.status` adds `detectedIos` / `detectIosError`. `logcat.start` with a UDID → `SimLogStream`
  (`simctl spawn <udid> log stream --style compact --predicate …`, backlog from `log show --last 5m`,
  same batching and `logcat.*` notifications); lines compacted to `HH:MM:SS L Process: [sub:cat] msg`
  (RN's `com.facebook.react.log` bracket dropped). Predicates: App JS = RN subsystem or the app's
  errors; Errors = errors/faults from processes under `/Containers/Bundle/Application/`; All = everything
  those processes log (the simulator's own daemons are never shown — they're hundreds of lines/s).
  `screen.start` refuses a UDID.
- **App:** booted simulators are merged into the device list as `AdbDevice(transport = "ios")`
  ("<name> · iOS · slim · 0.9 GB"), so Run / Logs reuse the Android panel; a "iOS simulators on the Mac
  · simslim" section lists Start…/Stop; `StartSimulatorDialog` (Slim, Show the Simulator window). The
  Logcat chip reads "Logs" for a simulator; the Screen tab / right column explain it's not available
  yet. `AndroidController.available` = adb **or** ios.
- **Tests:** helper test 7 (fake `xcrun` + fake `simslim` with a booted.json state file): list/sort/slim,
  already-running and bad-UDID refusals, stock start = `off` then `boot`, slim start = `on`, stop, Logs
  backlog + live lines and levels, screen refusal, `detectedIos` / iOS detect error. 9/9 pass.
- **Test environment:** a throwaway simulator "OC Helper Test" (`simctl create`, iPhone 17 / iOS 27.0)
  was used for every real run, so the user's simulators were never booted or slimmed.
- **Verified on the Mac** (loopback helper, scratch Expo SDK 58 blank-typescript project): `sim.list`
  (11 simulators, booted one with slim/memory); simslim slim start (~17 s), stock start (`off` → boots,
  6 s), stop (0.4 s); Metro start, then **real `expo run:ios --device <udid> --no-bundler`**: prebuild,
  CocoaPods, Xcode "Build Succeeded", installed and launched on the simulator → `succeeded`. Logs
  (App JS) showed `hello from the iOS simulator` / the `console.warn` line and the app's UIKit errors;
  a Metro reload re-ran the JS and the lines arrived again. `console.warn` comes through os_log at the
  same type as `console.log`, so it's not orange in Logs (Metro's log has it as a warning).
- **Verified on the physical phone** (vivo I2219, release build over USB install, real helper on the
  Tailscale IP, user's opencode service started for the test and stopped after): the device list showed
  "OC Helper Test · iOS · stock · 14.6 GB" as a device plus the "iOS simulators on the Mac · simslim"
  section (all 11); **Stop OC Helper Test** → "Stopped OC Helper Test" (simctl: Shutdown);
  **Start OC Helper Test…** → dialog (Slim on, window off) → "OC Helper Test is up", auto-selected,
  Run line switched to `npx expo run:ios --device 1206A158-…`, the tab reads **Logs**; launching the app
  on the simulator streamed its log live to the phone (with Metro off: "No script URL provided…" as an
  error); Screen shows the "not available for iOS simulators yet" message. Run wasn't pressed from the
  phone: the open folder was the user's `test-expo` (an iOS build would add `ios/` to it) — the iOS
  build itself was verified on the Mac above.
- **Not verified:** `react-native run-ios` (CLI path; only Expo was built), Simulator window option,
  a Mac without simslim (simctl path is covered by code only), a Mac without adb (panel with iOS only).
- Committed as `3c1780f`.

### iOS simulators — slice 2: live screen (`opencode-android-app-254`)

- **Tool choice (measured, user asked for the lowest latency; both installed with their OK):** AXe
  2.0.4 streams only JPEG for simulators (H.264 is physical-device only): ~19 fps, 58 KB/frame at scale
  0.5 even on a static screen (~1.1 MB/s). idb-companion 1.7.8 H.264 via the Python client works on
  Xcode 27: static screen ~0–40 KB/s. Same trigger, both streams at once: the change reached idb's stream
  220 / 490 ms after the trigger, AXe's 668 / 595 ms. Taps as CLI processes: `axe tap` 0.4 s, `idb ui tap`
  0.25 s. Chosen: talk gRPC to `idb_companion` from the helper (no Python, no CLI per event) — persistent
  `hid` stream for touches, `video_stream` for H.264. AXe and the scratch Python venv aren't used.
- **Helper `simscreen.ts`:** `findIdbCompanion` (`OC_HELPER_IDB_COMPANION` for tests) → feature
  `simscreen`. `SimScreenSession` (same interface as `ScreenSession`, chosen in `screen.start` for a
  UDID, shares `screensBySerial`): spawns `idb_companion --udid … --grpc-domain-sock <tmp>.sock
  --terminate-offline true`, waits for the socket, `describe` → points + pixels, `video_stream`
  H.264 (scale = maxSize / longest side → 368×800 for an iPhone 17, avg_bitrate = bitRate), `hid` stream.
  Each gRPC payload is exactly one access unit (checked: first = SPS, PPS, IDR; then single slices), so
  `AccessUnitPacker` emits config (SPS + PPS when changed) + frame/key frame — the app's H264Decoder is
  unchanged. Touch: one finger (first pointer), down/move = `press DOWN`, up = `press UP`, in points.
  Keys: home = HOME; recents = HOME twice (a bottom-edge swipe over HID only scrolled the app — tried
  slow and fast); back = drag in from the left edge (works in navigation stacks); menu = shake. If the
  companion dies while starting, its exit code + last stderr line is the error (not "Screen stopped").
  Deps added, pinned: `@grpc/grpc-js` 1.14.7, `@grpc/proto-loader` 0.8.1; `helper/proto/idb.proto` is
  the trimmed MIT proto (describe, hid, video_stream).
- **App:** `ScreenController.available` = `screen` or `simscreen`; `supports(serial)` picks the right
  feature per device; the Screen tab streams simulators; buttons' descriptions say Back (edge swipe) /
  App switcher / Dev menu (shake) for iOS; missing-tool messages name scrcpy or idb-companion.
- **Tests:** helper 10/10 — new `AccessUnitPacker` unit test (config once, IDR → kind 3, slice → 2,
  repeated SPS/PPS not re-sent); the iOS e2e test runs a stand-in companion that exits 3 and checks the
  error carries its reason; `simscreen` advertised.
- **Verified on the Mac** (loopback helper, throwaway slim simulator "OC Screen Test"): stream 368×800,
  1 config + 2 key frames + 166 frames ≈ 290 KB in 6 s; tap opened Settings, drag scrolled it, Home,
  app switcher (double Home), Back out of General; `idb_companion` gone after `screen.stop`.
- **Verified on the physical phone** (vivo I2219, release build, helper restarted by request on the
  Tailscale IP): picked "OC Screen Test · iOS · slim · 3.3 GB" → Screen showed Settings live; tapping
  General on the phone opened it, Back returned, a swipe on the stream scrolled the list, Home and the
  app switcher worked; Stop → companion gone on the Mac, Show again → new companion, closing the sheet
  → gone.
- **Not verified:** the shake → React Native dev menu (no RN app running on the simulator during the
  phone test), rotation, iPad simulators, streaming over 4G (Wi-Fi + Tailscale only), the ≥ 1000 dp
  right column with a simulator.

### UI/UX pass 1 (whole app, after Phase 2)

Asked: "make the UI UX good, functional and pretty" (whole app). Audit on the phone first, then:
- **Bug found by the audit (functional):** on opencode 2.x, any file bigger than one network packet
  failed to open ("index.tsx: Read failed") from the tree *and* Recent; `app.json` (1 KB) opened. Cause:
  `fsRead` is `@Streaming`, so `ResponseBody.bytes()` ran on the caller's (main) thread →
  `NetworkOnMainThreadException` (no message) once the body didn't arrive with the headers. A JVM test
  can't see it (no StrictMode). Fix: `OpenCodeApi.readBody` reads on `Dispatchers.IO` (editor reads and
  the read-only viewer); a message-less exception now reports its type ("Read failed: <Type>").
- **Design system** `ui/common/Ui.kt`: `EmptyState` (tinted icon, title, help, action), `Segmented`
  (panel tabs / filters, with count or dot badges), `StatusPill`, `SectionHeader`, `OcCard` (12 dp radius,
  1 dp border), `Banner` (dismissible error/info). 16 dp gutter, 8 dp control radius.
- **Navigation:** Terminal and Settings were pushed pages (back arrow, bottom bar gone) although the
  bar looked like tabs. Now all four are tabs: Sessions is the root, switching replaces the current tab
  (`popUpTo(sessions) { saveState }`, `restoreState`), crossfade between tabs (slide for everything
  else), Back from any tab → Sessions. Settings from the server list keeps its back arrow (route flag
  `tab`, explicit because a server's id can be the empty string — servers saved by older versions).
  A live terminal takes the whole screen; its back arrow / the back gesture return to the list.
- **Theme:** Material's default purple containers (selected segmented buttons, tonal buttons) replaced
  by the accent (`secondaryContainer = accentSoft`); status / navigation bar icons follow the app theme
  (they were white on white in light mode).
- **Screens:** Sessions — bordered cards, time top-right, one quiet meta line (Working/Retrying/Shared
  pills, +/− in green/red, compact tokens "12k tok", cost), redundant folder path dropped (the list is
  per folder), proper empty states, error as a banner. Chat — one composer card (text, then `/`, attach,
  agent/model/variant pills, round Send that's filled only when there's something to send), user bubble
  with a tail corner, assistant byline "• agent · model" without the provider prefix, quiet "Diff".
  Terminal — empty state, cards with Running pill and `~`-shortened cwd. Settings — section headers,
  bordered cards, Mac helper "Live" pill + feature chips in words instead of "pair, lsp, …". Editor —
  Save is filled only with unsaved changes, else a quiet "✓ Saved". Android/iOS panel and Metro —
  segmented tabs/filters (Problems count badge), device picker as a pill, Run / Start as the primary
  button, Stop in red, banners.
- **Checked on the phone** (vivo I2219, release build), dark and light: all four tabs + back,
  Sessions, Chat, Terminal empty state, Settings, Code → Android panel, Metro panel; the user's theme was
  put back to Dark afterwards. Unit tests pass.
- **Not done yet (next pass):** server list, Files list rows, Diff screen, read-only viewer, editor tab
  strip, chat tool/reasoning cards, permission/question sheets, tablet two-pane layouts.

### UI/UX pass 2 (`opencode-android-app-2iv`)

- **Server list:** cards with a computer tile, name + address (no scheme), whole card connects, ⋮ for
  Edit / Remove; Remove now asks first (it deleted the server on one tap); empty state for a fresh install.
- **Chat:** tool calls are compact rows — tool icon (shell, edit, read, search, web, task, todo),
  tool name + subject (the tool's title, else the file path's last three segments / command / pattern
  from its input), spinner / ✓ / ⚠ — tap to expand Input and Output code blocks; failures keep a red
  border and show the error. Reasoning is a "💡 Thinking" row that expands into text with a rule. The
  "step / step finished" dividers are gone.
- **Permission sheet:** shield icon, "opencode wants permission" + what, the command in a code block,
  **Allow once** as the full-width primary button, Always allow (tonal) / Deny (outlined, red).
- **Files:** shared section headers, file icons tinted by type (code, data, styles, images).
- **Diff:** loader, empty state ("No changes in this session" ✓), file headers with the directory muted
  and the file name bright, +/− counts, › when it opens in the editor.
- **Editor:** the active tab has an accent underline.
- **Checked on the phone** (dark): server list, Files, chat tool rows and Thinking (in a session from
  another project folder — viewed only, folder switched back to test-expo), Diff empty state, editor tabs.
  The permission sheet wasn't triggered (it would need opencode to ask for a real action).
- **Then (same bead):**
  - **Question sheet:** same layout as the permission sheet (blue ? icon, "+N more" pill). Answers are
    bordered rows that turn accent when picked. The free-text field is rounded. **Send answer** is the
    full-width primary button and Dismiss is quiet.
  - **Read-only viewer:**
    - the title is the file name, with its folder underneath;
    - a strip shows a language pill, the line count and "Read only";
    - code sits on the page background; the target line gets an accent tint and a bright number;
    - loading, error ("Try again") and binary files use the shared empty state.
  - **Light mode:** the syntax highlighter (viewer and chat code blocks) used dark-theme colors only,
    which washed out on white. It now has GitHub-light tones when the theme is light.
  - **Checked on the phone in light mode:** server list, sessions, chat, viewer, Diff empty state.
    Dark was restored afterwards. The question sheet wasn't triggered, because it needs a model to ask a
    real question.
- **Still open:** tablet layouts beyond the existing `WideTwoPane` for Files/viewer (not checked on a
  tablet).

### Latency and data (`opencode-android-app-859`)

Measured first: on opencode 2.x the chat re-fetched the newest 50 messages after every pause in a
reply. That was 32–38 KB gzipped for normal sessions and 527 KB for a large one (1.1 MB raw), so a turn
with about 20 tool calls could use about 10 MB. Text only showed up at the pauses.

- **Live 2.x streaming:** `data/net/V2LiveEvents.kt` turns the 2.x events into the patches the chat
  already applies.
  - `inbox.enqueued` becomes the user message, using the stored id.
  - `step.started` / `step.ended` become the assistant message (model, then tokens, cost and finish).
  - `text.*` / `reasoning.*` become a live part that grows with each delta.
  - `tool.input.started` / `called` / `success` / `failed` become the tool part, with the call id as
    its id.
  - Counters and markers are ignored. Anything else still maps as before.
  - Captured from a real 2.3.2 run; `V2LiveEventsTest` replays that sequence.
- **One reconciling fetch per turn** (on `session.idle`), not one per burst.
  - Patching: a delta for a part the app doesn't have yet keeps that part's id. Before this fix it got
    an empty id. Reasoning deltas now append too.
- **Tail refresh:**
  - After the first full page, a refresh fetches only the newest messages: at least 6, more if live
    parts need covering. It merges them in.
  - If that tail doesn't overlap what's already loaded (the stream was down for a while), it falls
    back to a full page.
  - Reconnecting (`server.connected`) always fetches a full page.
  - The status, session and todo requests run in parallel with it: one round trip instead of four in
    a row.
- **Screen data saver:** on a metered network (mobile data, a metered hotspot) the live screen
  streams at 600 px, 500 kbps and 20 fps (about 4 MB/min) instead of 800 px, 1.5 Mbps and 30 fps
  (about 11 MB/min). A "Data saver" tag shows on the picture. Works for Android devices and iOS
  simulators.
- **Tailscale relay:**
  - `tailscale ping` to the phone went via DERP (Singapore, 34–87 ms) with no direct connection.
  - `tailscale netcheck` on the Mac: `MappingVariesByDestIP: true` (a "hard" NAT) and no port
    mapping (no UPnP / NAT-PMP / PCP on the router).
  - With the phone on mobile data (carrier NAT), a direct path can't be set up, so traffic goes
    through the relay.
  - Fixes on the user's side: turn on UPnP or NAT-PMP on the router, or have the phone on the same
    Wi-Fi.
- **Chat follow fix (found on the phone):** the chat scrolled to the *top* of the last message and only
  re-scrolled when a new part appeared, so a long streaming reply sat on its first lines behind the
  "scroll to bottom" button. Now "at the bottom" means the list can't scroll further, following scrolls
  to the very end (including the "working" row), and it re-follows as streamed text grows (about every
  120 characters). The jump button and sending use the same scroll-to-end.
- **Checked:**
  - unit tests (27 run, 3 integration tests skipped as usual);
  - release build;
  - event shapes from a real prompt in a throwaway folder.
  - **On the phone** (opencode 2.3.2, throwaway folder, a byte-counting proxy between the app and
    opencode):
    - The user message, a tool card (read → ✓) and step usage appeared live.
    - With DeepSeek V4.1 Flash, text streamed in during the reply (about 70 events over 11 s), and the
      chat followed it after the fix.
    - Gemini 4 Neon sends its answer in one burst after about 40 s of silent thinking, so there's
      nothing to stream there.
    - Traffic: no message downloads during a reply; one fetch when it ends, 2–28 KB. The old code
      downloaded a 50-message page after every pause.
  - **Screen data saver, checked on the phone on mobile data:** the app reached the helper over
    Tailscale and mirrored the phone itself. The "Data saver" tag showed, and scrcpy on the phone ran
    with `max_size=600 max_fps=20 video_bit_rate=500000`.
  - **Fixed while there:** the overlay's "Show screen" / "Retry" button didn't respond. The
    touch-forwarding layer consumed every tap even with no stream running. Now it only takes
    touches while the stream is live.

### Phase 2 design notes

opencode gives the app files, PTYs and sessions, but no code intelligence and no Android/React Native
dev loop. A small companion daemon on the Mac would add those; the app keeps talking to opencode for
everything else and only lights up helper features when the helper answers. Tracked in bd as epic
`opencode-android-app-tuc` (+ `.1`–`.5`).

- **Process:** one long-running daemon (Node or Kotlin — decide when building; Node fits tsserver /
  Metro / Prettier best), installed as a login item, listening only on the Tailscale interface
  (`tailscale ip -4` / MagicDNS name). One WebSocket for RPC + streams, JSON-RPC-style messages; binary
  frames for video.
- **Discovery + pairing (`tuc.1`):** the app looks for `http://<mac>.ts.net:<port>/helper/info` for the
  active server's host (same Mac as opencode). First connect shows a 4-digit code on the Mac (macOS
  notification + terminal); confirming it issues a device token stored in `ServerStore`, sent as
  `Authorization: Bearer`. Tokens are revocable from the helper.
- **Language server (`tuc.2`):** the helper runs `typescript-language-server` (tsserver) against the
  real project and `node_modules`, and bridges LSP over the socket. The editor gets an LSP client
  extension (`@codemirror/autocomplete` sources + `@codemirror/lint` diagnostics + go-to-definition +
  rename) in the same bundle; the popup uses the `popup`/`popupBorder` roles. Format/lint on save:
  Prettier/ESLint run by the helper *before* the save is written, returning the formatted text.
  Saving stays on `FileWriter` (or moves to the helper when present: atomic, faster).
- **Metro (`tuc.3`):** start/stop/reload (`r` / `/reload` on the Metro port), logs streamed into a
  **Metro** tab of the bottom panel; red-box errors surfaced as notifications.
- **adb (`tuc.4`):** build + install (`./gradlew installDebug` / `npx react-native run-android`),
  wireless pairing of the phone itself (`adb pair` / `adb connect <phone-tailscale-ip>`), a **Logcat**
  tab with filters.
- **Emulator screen (`tuc.5`):** fills the reserved 300 dp right slot (≥ 1000 dp) and the landscape
  split: the emulator's gRPC/WebRTC stream (`-grpc` + `emulator_controller`), or scrcpy-server as a
  fallback, decoded in a `SurfaceView`; taps/swipes mapped back as touch events; Back/Home/rotate/
  shake (dev menu)/screenshot toolbar.
- **App integration:** `HelperClient` in `data/net`, `HelperState` in `AppViewModel` (absent / pairing
  / ready); Metro/Logcat tabs and the emulator pane render only when ready.

## Features done

Incremental SSE part patching · adaptive power-aware polling (3s busy / 15s fg / 60s bg) · todos
panel · agent+model switcher + reasoning-variant chip (per session, last pick = default for new sessions; scrollable chip row, short model name) · slash commands (`/command`) · copy message ·
session status dots · rename session · per-message model/tokens/ctx/$ · session totals · queued
indicator · per-message diff · project search (`/find`, `/find/file`) · offline cache (Room) ·
notifications (foreground service toggle) · mDNS scan + Tailscale remote field · **redesigned folder
picker** (tappable folder card + scrollable sheet, filter when the project list grows, active-folder
checkmark, server-default + custom-path entries, no stale-session flash on switch) ·
message pagination (load-older on scroll + pull-to-refresh) · in-app permission prompts
(Allow once / Always / Deny) · image + file attachments from the phone (rendered inline; tap an image
for fullscreen pinch-zoom; V2 user `files` attachments are mapped back on load) ·
agent questions in-app (single/multi-select + custom text, answer or dismiss) ·
in-app update check (launch-time prompt → download → install APK) · session actions (fork / revert /
unrevert / delete message, from long-press menus) · share/unshare session links · sessions search +
server switcher · clickable links in chat · file viewer line numbers + jump-to-line + per-line
LazyColumn virtualization · code blocks with copy button · sticky diff headers · scroll-to-bottom FAB ·
relative timestamps · empty states · SSE-liveness battery saving · system/dark/light theme toggle
(`OcTheme.colors`) · markdown tables + ordered/nested lists · summarize session ·
retry failed send + cancel queued · WorkManager notification fallback (FGS 6h cap) ·
home-screen status widget · two-pane layout on wide screens (≥720dp) · shared motion tokens ·
standard Android push/pop navigation transitions (`NavHost` slide-in-from-right / slide-out on pop,
240ms `Motion.MEDIUM`; replaces navigation-compose's default cross-fade) ·
Material 3 pass: full color-role set (surfaceContainer*, inverse, outlineVariant) so M3 components
match the custom palette, Settings screen (Appearance / Notifications / OpenCode Go / About / Security),
bottom `NavigationBar` on Sessions, `ListItem` rows, Extended FABs, chat overflow menu,
consistent `TopAppBar` colors. Markdown / code blocks / diffs stay custom-rendered ·
**in-app terminal** (full PTY + ANSI/xterm emulator, bottom-nav item; paste from clipboard +
long-press to copy visible screen / whole scrollback) ·
**OpenCode Go plan usage** in Settings (5h/weekly/monthly bars, refresh + auto-refresh, masked key
with show/hide) · shortened project paths (`…/parent/folder` in rows, header, folder bar) ·
**connection status chip** in the Sessions + Chat app bars (green Live / amber Checking… / red
Offline; tap for version + latency + recheck). `AppViewModel.connection` polls `/global/health`
every 30 s and on server switch (probe client is cached per server as `probe-<id>`) — health-based,
not SSE-liveness. ·
chat viewport is no longer yanked while reading old messages (follow-tail only when parked at the
bottom; sending always snaps to the latest turn) ·
**New session opens it immediately** (`createSession()` returns the created id → `onChat`, so the
FAB / empty-state button navigates straight into the chat; two-pane selects it in place) ·
**SSE events are session-filtered** (`ChatViewModel.isOwnSession` checks `sessionID` on
`message.updated`/`message.part.updated`/`message.removed`/`message.part.removed`/`session.status`/
`session.idle`/`todo.updated` before patching). Fixes chat glitching when a folder holds several
sessions — foreign-session events used to bleed into the open chat. ·
**git branch bar** on the Sessions screen (current branch from `/vcs`; tap to list local branches,
switch, or create one — git runs server-side through a one-shot PTY, see integration fact 17) ·
**code editor (Phase 1)**: Code tab (tree + recent files), CodeMirror 6 editor with tabs, dirty dots,
undo per tab, 9 languages + plain, search/replace, go to line, soft wrap, coding key row, hardware
shortcuts (Ctrl+S/P/F/G, Ctrl+/, Tab/Shift+Tab), Go-to-file palette, hash-verified saves (native 2.2
route or atomic PTY writer), conflict prompt (Reload/Overwrite/Show diff), unsaved-changes guards,
tablet/landscape IDE layout (tree │ editor │ Terminal/Git panel, emulator slot), git branch + N changed,
Changes sheet with diffs, Commit (see "Code editor (Phase 1)").

## Gotchas

- **Protocol detection** (`OpenCodeApi.detect`) must not fall back to V1 on a *network* error: the
  result is cached per process, so opening the app with Tailscale off pinned a 2.x server to 1.x routes
  and every call parsed the web UI HTML (`Unexpected JSON token … '<'`) until a force-stop. Now the
  IOException propagates and the next call probes again (seen on a real phone, Nov 2026).
- **Editor:** `git status --porcelain` lines start with a significant space (` M file`) —
  `GitRunner.parse` trims blank lines only; a full `trim()` silently dropped the first change.
- **Editor:** never remove a bottom-panel composable just because the IME is up — typing in the
  terminal or the Commit dialog also raises it, and dropping the panel killed the dialog mid-typing.
  The panel/nav only collapse while CodeMirror itself has focus (`onFocus` bridge) on short screens;
  the key row likewise shows only while the editor is focused.
- **Editor:** `configChanges` now includes `smallestScreenSize|keyboard` — a fold/unfold or attaching a
  hardware keyboard otherwise recreated the Activity (nav reset to Sessions, WebView rebuilt).
- **Editor:** the `EditorViewModel` is scoped to the Activity (`rememberEditorViewModel`) and keyed by
  server + folder, so the Code tab, the editor route and the tablet layout share tabs; switching
  folders gives a fresh set of tabs.

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
  (3) the stream carries events for EVERY session in the folder, so consumers must filter by
  `sessionID` (see `ChatViewModel.isOwnSession`) or a busy/idle session elsewhere glitches this one.
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
  **Manual check (Settings → About):** `checkForUpdate(force = true)` sets a `checkingUpdate` flag
  (the row shows a spinner + "Checking…" and ignores repeat taps), clears the stale message, and
  reports the outcome in the row's supporting text ("You're on the latest version (v…)" / "v… is
  available" / "Could not reach GitHub"). There is **no sticky "dismissed" flag** in MainActivity —
  it used to suppress the prompt for the rest of the process, so after tapping "Later" once a manual
  check set `Available` but showed nothing at all (looked unresponsive). Dismissing still records the
  skipped version so the launch-time check stays quiet.

## Open / next ideas

- Remote `http(s)` image parts (currently only `data:` URLs render inline), image
  thumbnails/compression before upload, incremental refresh for `session.something` events
  (currently full refetch), update check only over Wi-Fi + manual "check now" button, widget for
  multiple sessions, richer wide-screen layout (three panes / list-detail for servers),
  per-model Go usage / Zen balance (not exposed by the public usage endpoint).

## Verification

Code editor (Nov 2026, opencode **2.2.6** via `make serve`, emulator Pixel API 36 / WebView 149):
opened `src/screens/Signup.tsx` from the Code tab, edited with the soft keyboard + key row (pairing
quotes, ←/→, undo), saved → bytes on the Mac matched (hash-verified), `git status` showed `M`; edited
the file on the Mac then saved on the phone → conflict dialog, diff, Overwrite; Ctrl+S / Ctrl+P / Ctrl+F
from a hardware keyboard; landscape phone (rail + tree + editor + live terminal panel); tablet portrait
and landscape (tree │ editor │ Git panel │ emulator slot); Commit with a message containing quotes and
`$(…)` landed verbatim. JVM tests: `EditorFilesTest` (14) + `FileWriterIntegrationTest` (3, opt-in
`OC_IT=1`: native route, PTY writer with a 1.5 MB CRLF file, PTY exact-bytes read, conflicts, `../`
refusal). **Not yet verified:** a real 1.x server (PTY read/write paths are exercised against 2.2.6 by
forcing them), very large files' typing performance. **Physical device (vivo I2219, Android, WebView
157):** release build installed over 0.1.52 (data kept); over USB (`adb reverse` → `make serve`) opened
`Signup.tsx`, typed with the soft keyboard + key row (`=`, `{ }` pairing, auto-indent), saved (Mac
bytes correct, git count updated), conflict prompt + **Reload**, landscape IDE layout; then browsed the
real `onboard_v3` project read-only over Tailscale on the user's opencode service.

Validated against opencode **1.18.31** live server: sessions, messages, prompt_async, command, diff,
SSE, file list/content, find/find-file, provider/agent, PATCH title. `make serve` starts a local test
server on :4199 (password `secret`).

Validated against opencode **2.0.26** live server (on a real device): version auto-detect, sessions
list + `?directory=` filtering, project/folder picker, git branch bar, chat load/render
(text/reasoning/tool), send + live SSE streaming, agent/model/command lists, files list/navigate/read
(+ syntax highlighting), file find, terminal create + shells + ticket + websocket, session diff
(patch rendering). V1 path is unchanged.

Checked against **2.1.4**: user-message attachment shape (`files:[{data,mime,source,name?}]`, inline
base64 for phone/clipboard images) and V2 image mapping. (The 2.1.4 checks were API-level; the
fullscreen image viewer is Compose-only and needs a device smoke test.)
