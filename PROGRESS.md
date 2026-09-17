<!-- ship: v0.1.12 (versionCode 13) -->

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
   is a different process/event-bus → no live TUI↔app sync.
6. **Auth:** HTTP Basic (`opencode` user + password). 401 otherwise.
7. **Totals formula** (matches TUI `sidebar/context.tsx`): tokens = last assistant message
   `input+output+reasoning+cache.read+cache.write`; % = tokens / model `limit.context`;
   $ = `session.cost`.

## Features done

Incremental SSE part patching · adaptive power-aware polling (3s busy / 15s fg / 60s bg) · todos
panel · agent+model switcher (persisted per server) · slash commands (`/command`) · copy message ·
session status dots · rename session · per-message model/tokens/ctx/$ · session totals · queued
indicator · per-message diff · project search (`/find`, `/find/file`) · offline cache (Room) ·
notifications (foreground service toggle) · mDNS scan + Tailscale remote field · directory/folder picker.

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
- mDNS resolves the **LAN IP**, not Tailscale; use the "Tailscale (remote)" field (`100.x.y.z` or
  `<mac>.ts.net`, https if `.ts.net`).

## Open / next ideas

- Image attachments (`image/*` file parts), share-session links, LazyColumn line virtualization for
  very large files, WorkManager notification fallback (FGS has a 6h/day limit on Android 15+),
  incremental refresh for `session.something` events (currently full refetch).

## Verification

Validated against opencode **1.18.31** live server: sessions, messages, prompt_async, command, diff,
SSE, file list/content, find/find-file, provider/agent, PATCH title. `make serve` starts a local test
server on :4199 (password `secret`).
