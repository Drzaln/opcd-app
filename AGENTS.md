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
```

Requirements: JDK 17, Android SDK 36 (compileSdk), Gradle 8.13 (wrapper included).
No local.properties needed — `ANDROID_HOME`/SDK on PATH is enough.

## How the app talks to opencode

- Mac runs `opencode serve` (NOT `web` — web opens a browser) bound to `0.0.0.0` or the Tailscale IP,
  with `OPENCODE_SERVER_PASSWORD` set.
- App talks plain HTTP(S). Auth = HTTP Basic (`opencode` user + password). Server returns 401 otherwise.
- **Instance routing:** sessions/files are scoped per project folder. Almost every call passes an optional
  `?directory=<path>` query to select the project instance (sessions, messages, prompt_async, diff, abort,
  todos, file list/content, session status). The app keeps a global `currentDirectory` in `AppViewModel`
  (`setDirectory()`); pick a folder from the Sessions screen. `/project` lists known projects.
- Endpoints used (see `data/net/OpenCodeApi.kt`): `/global/health`, `/project`, `/project/current`, `/path`,
  `/session`, `/session/status`, `/session/{id}/message`, `/session/{id}/prompt_async`,
  `/session/{id}/diff`, `/session/{id}/abort`, `/file`, `/file/content`, `/agent`, `/command`.
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
- Chat refresh strategy: on SSE events re-fetch `/session/{id}/message`, debounced 250ms (`ChatViewModel`).
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

- Chat refetches full message list on events; incremental part patching would be cheaper/faster.
- File viewer truncates >5000 lines; virtualized line rendering (LazyColumn) is the upgrade path.
- Not yet built: todos/commands/agents screens, share-session links, offline cache.
- Verified against opencode 1.18.31 live server (sessions, messages, prompt_async, diff, SSE, file list/content).