# OpenCode Mobile — Android client for opencode

Native Android (Kotlin + Jetpack Compose) client for the opencode HTTP server API.
Lets a phone connect over Tailscale to an opencode server running on a Mac:
view/resume sessions, chat (live SSE streaming), browse the project, view code with
syntax highlighting, and review session diffs.

## Quick start

```bash
make build        # assembleDebug → app/build/outputs/apk/debug/app-debug.apk
make release      # assembleRelease (signed w/ debug key unless keystore.properties exists)
make install      # adb install the debug APK
make serve        # local opencode test server on :4199 (password: secret)
make ship         # bump version, build release, push main + v* tag (see Release workflow)
```

Requirements: JDK 17, Android SDK 36 (compileSdk), Gradle 8.13 (wrapper included).
No local.properties needed — `ANDROID_HOME`/SDK on PATH is enough.

## Release workflow (when the user says "ship it" / "push it" / "release it")

Always do ALL of these, in order:

1. **Bump the version**: run `./scripts/ship.sh [patch|minor|major|<x.y.z>]` (default `patch`).
   It bumps `versionCode` (+1) and `versionName` in `app/build.gradle.kts`, builds `assembleRelease`,
   commits, pushes `main`, tags `v<versionName>`, and pushes the tag. The tag triggers CI
   (`.github/workflows/build.yml`) which builds the APK and creates a GitHub Release.
2. If the user asked for a specific bump type, pass it (e.g. `make ship minor`); otherwise default to `patch`.
3. **Verify**: confirm the CI run on the tag completes and a GitHub Release with `app-release.apk` exists
   (check via `curl https://api.github.com/repos/Drzaln/opcd-app/releases` or the Actions tab).
4. If the CI release step 403s despite the workflow's `permissions: contents: write`, the repo-level
   default may still be read-only — ask the user to enable Settings → Actions → General → Workflow
   permissions → "Read and write permissions", then re-run.
5. Never create a version bump commit or tag without the user asking to ship (or saying ship/push/release).

Note: release APK is signed with the debug key unless `keystore.properties` + CI signing secrets exist.

## How the app talks to opencode

- Mac runs `opencode serve` (NOT `web` — web opens a browser) bound to `0.0.0.0` or the Tailscale IP,
  with `OPENCODE_SERVER_PASSWORD` set.
- For real-time two-way sync, run the **TUI itself** on a fixed port instead of a separate serve/web:
  `OPENCODE_SERVER_PASSWORD=secret opencode --hostname <tailscale-ip> --port 4096`. The TUI IS the
  server; the app connects to that same server, so app↔TUI updates flow both ways. (A separate
  `opencode serve` is a different process → its own event bus → no live TUI updates.)
- App talks plain HTTP(S). Auth = HTTP Basic (`opencode` user + password). Server returns 401 otherwise.
- **Instance routing:** sessions/files are scoped per project folder. Almost every call passes an optional
  `?directory=<path>` query to select the project instance (sessions, messages, prompt_async, diff, abort,
  todos, file list/content, session status). The app keeps a global `currentDirectory` in `AppViewModel`
  (`setDirectory()`); pick a folder from the Sessions screen. `/project` lists known projects.
- Endpoints used (see `data/net/OpenCodeApi.kt`): `/global/health`, `/project`, `/project/current`, `/path`,
  `/session` (GET list + POST create + GET/PATCH/DELETE by id), `/session/status`,
  `/session/{id}/message` (GET + POST), `/session/{id}/prompt_async`, `/session/{id}/command`,
  `/session/{id}/diff`, `/session/{id}/abort`, `/session/{id}/todo`, `/file`, `/file/content`,
  `/file/status`, `/agent`, `/provider`, `/command`.
- Live updates come from the `/event` SSE stream. The app passes `?directory=` to scope events to the
  selected project instance.
- **Quirk:** SSE event type lives in the JSON body (`data: {"type":"message.updated","properties":{...}}`),
  NOT the SSE `event:` field. Parsed in `OpenCodeRepository.ReconnectingListener`.
- **Quirk:** `/session/{id}/diff` returns `FileDiff[]` where `before`/`after` are FULL FILE CONTENTS,
  not hunks. The app computes line diffs client-side (`ui/common/DiffLines.kt`, java-diff-utils).
- DTOs live in `data/model/Dtos.kt` (kotlinx.serialization, `ignoreUnknownKeys = true`).
  Ground truth: `packages/sdk/js/src/gen/types.gen.ts` in the opencode repo, or curl a live server.

## Architecture

Single `:app` module. No DI framework.

- `OpenCodeApp` (Application) owns `ServerStore` (DataStore prefs) + `OpenCodeRepository` (Retrofit/OkHttp/SSE factory).
- `data/net`: `ServerStore` (server list + active id), `OpenCodeRepository`, `OpenCodeApi` (Retrofit iface),
  `NsdDiscovery` (mDNS auto-detect of `opencode-<port>` on `_http._tcp` — resolves the LAN IP, NOT the
  Tailscale IP; the servers screen has a separate "Tailscale (remote)" field for that), `ServerConfig`.
- `AppViewModel` (MainActivity): global state — servers list, active server, `probe()` health check.
- Screens (nav routes in `MainActivity.Routes`): servers → sessions → chat / files / file viewer / diff.
- Screens fetch their `ServerConfig` from `appVm.servers`; ViewModels are built inline:
  ```kotlin
  val vm: ChatViewModel = viewModel(
      key = "chat_${serverId}_$sessionId",
      factory = viewModelFactory { initializer { ChatViewModel(app, server, sessionId) } },
  )
  ```
- `ui/common`: `CodeHighlighter` (Prism4j), `Markdown` (lightweight block/inline renderer), `DiffLines`, `CodeBlock`.

## Conventions

- Kotlin, Jetpack Compose (Material3), dark theme only. Colors in `ui/theme/Color.kt`.
- No comments unless they explain a non-obvious decision.
- Chat renders messages by `part.type` in `PartView` (ChatScreen.kt). Add new part types there.
- Chat has slash-command support: input matching `/<name> args` where `<name>` is a known `/command`
  is routed to `session/{id}/command` (with the selected agent/model); otherwise it goes to `prompt_async`.
  Agent + model selectors are persisted per server via `ServerStore`.
- Chat message flow: SSE `message.part.updated` (with `delta` for text streaming) is patched
  incrementally in `ChatViewModel`; `message.updated`/`part.removed`/`message.removed`/`todo.updated`
  apply the same way. Fallback: debounced 400ms full refetch (`scheduleFullRefresh`), plus adaptive
  polling (3s while busy / 15s foreground / 60s background). A `send()` also re-fetches ~400ms later
  to pick up the persisted user message.
- `formatTime`, diff line keys, and DTO defaults follow existing patterns — mirror, don't invent.

## Gotchas

- **prism4j 2.0.0 bundles only:** markup, clike, css, javascript, java, kotlin, python, go, c, cpp,
  csharp, dart, swift, sql, json, yaml, markdown, git, groovy. Adding a language means it must be in
  the ported list AND in `@PrismBundle(include = [...])` (`ui/common/CodeHighlighter.kt`).
  Annotation element is `include` (not `includes`).
- `org.jetbrains:annotations-java5` is excluded from prism4j to avoid classpath dupes (app/build.gradle.kts).
- Version set is deliberately pinned: AGP 8.13.0 / Gradle 8.13 / Kotlin 2.2.20 / compose-bom 2025.09.00.
  Don't bump blindly — the prism4j exclusion and kapt setup are sensitive.
- Release signing: real keystore only if `keystore.properties` exists (gitignored); CI injects it from
  secrets. Otherwise release falls back to the debug key so the APK is installable.

## CI

`.github/workflows/build.yml`: `assembleRelease` on push to `main`; tag `v*` → GitHub Release with APK.
Optional real signing via secrets: `ANDROID_SIGNING_KEY_BASE64`, `ANDROID_SIGNING_STORE_PASSWORD`,
`ANDROID_SIGNING_KEY_ALIAS`, `ANDROID_SIGNING_KEY_PASSWORD`.

## Testing against a real server

```bash
make serve                       # background opencode server on :4199 (password "secret")
curl -u opencode:secret http://127.0.0.1:4199/session
curl -u opencode:secret http://127.0.0.1:4199/file/content?path=settings.gradle.kts
```
(Note: this shell is zsh — no word-splitting on unquoted vars; pass curl args literally.)

## Status / next ideas

- SSE handling is incremental; the remaining cost is that `scheduleFullRefresh` still refetches the
  full message list on non-part events (session/diff/compact).
- File viewer truncates >5000 lines; virtualized line rendering (LazyColumn) is the upgrade path.
- Not yet built: dedicated todos/commands/agents screens (todos already render in chat), share-session
  links, offline cache, `/file/status` usage in the UI.
- Verified against opencode 1.18.31 live server (sessions, messages, prompt_async, diff, SSE, file list/content).