# OpenCode Mobile — Android client for opencode

Native Android (Kotlin + Jetpack Compose) client for the opencode HTTP server API.
Lets a phone connect over Tailscale to an opencode server running on a Mac:
view/resume sessions, chat (live SSE streaming), browse the project, view code with
syntax highlighting, and review session diffs.

## Quick start

See **`PROGRESS.md`** for the compact project handoff (architecture, integration gotchas, status).

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

0. **Update ALL docs before shipping** (ship.sh auto-stamps the version, but the *content* must be
   current first):
   - `PROGRESS.md` — compact handoff (features done, gotchas, open items).
   - `README.md` — user-facing feature/screen list, setup, usage.
   - `AGENTS.md` — architecture, conventions, endpoints, status/next ideas.
   - `README.linux.md` — if the Linux/server notes changed.
   - `CLAUDE.md` — if it mirrors anything you touched.
   If a change adds a screen/feature or alters behavior, it must be reflected in the relevant docs
   (at minimum `PROGRESS.md` + `README.md`).
1. **Bump the version**: run `./scripts/ship.sh [patch|minor|major|<x.y.z>]` (default `patch`).
   It bumps `versionCode` (+1) and `versionName` in `app/build.gradle.kts`, stamps `PROGRESS.md`,
   builds `assembleRelease`, commits, pushes `main`, tags `v<versionName>`, and pushes the tag.
   The tag triggers CI (`.github/workflows/build.yml`) which builds the APK and creates a GitHub Release.
2. If the user asked for a specific bump type, pass it (e.g. `make ship minor`); otherwise default to `patch`.
3. **Do NOT poll the GitHub API to verify** (rate limits — the user has said so explicitly). No
   `curl https://api.github.com/repos/.../releases` and no Actions-run polling. After `ship.sh`
   prints "Shipped vX.Y.Z", you are done: report the version, commit and tag that were pushed, and
   tell the user to check the Actions/Releases tab in the browser if they want to watch CI.
4. If the CI release step 403s despite the workflow's `permissions: contents: write`, the repo-level
   default may still be read-only — ask the user to enable Settings → Actions → General → Workflow
   permissions → "Read and write permissions", then re-run.
5. Never create a version bump commit or tag without the user asking to ship (or saying ship/push/release).
6. `ship.sh` does `git add -A`; keep build artifacts (`.kotlin/`, logs) out of the tree first.
7. **Reindex this project** into the codebase-memory-mcp knowledge graph after shipping, so the graph
   reflects the released code. Call `index_repository(repo_path="/Users/rizal/perkodingan/opencode-android-app")`
   (it is not indexed by default; index even if it reports "project not found").

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
  Tailscale IP; the servers screen has a separate "Tailscale (remote)" field for that), `ServerConfig`,
  `GoUsageClient` (OpenCode Go plan usage — external Bearer call, see below).
- `AppViewModel` (MainActivity): global state — servers list, active server, `probe()` health check.
- Screens (nav routes in `MainActivity.Routes`): servers → sessions → chat / files / file viewer / diff,
  plus `Settings` (Appearance / Notifications / OpenCode Go / About / Security).
- Screens fetch their `ServerConfig` from `appVm.servers`; ViewModels are built inline:
  ```kotlin
  val vm: ChatViewModel = viewModel(
      key = "chat_${serverId}_$sessionId",
      factory = viewModelFactory { initializer { ChatViewModel(app, server, sessionId) } },
  )
  ```
- `ui/common`: `CodeHighlighter` (Prism4j), `Markdown` (lightweight block/inline renderer), `DiffLines`, `CodeBlock`.
- OpenCode Go usage: `GET https://opencode.ai/zen/go/v1/usage` with `Authorization: Bearer <go key>`
  → `usage.{rolling,weekly,monthly}.{status,percent,resetsAt}`. **Not** on the local server — the app
  stores the user's Go API key (`ServerStore` `opencode_go_api_key`) and calls opencode.ai directly
  (`GoUsageClient`, `ui/settings/UsageViewModel.kt`). Poll-based; aggregate only.

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

`.github/workflows/build.yml`: runs **only on tag `v*`** (or manual dispatch) — `assembleRelease` +
GitHub Release with the APK. Plain pushes to `main` do NOT trigger CI.
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

- Done: incremental SSE part patching, adaptive power-aware polling, todos panel, agent/model
  switcher (persisted per server), copy message, session status dots, rename, per-message
  model/tokens/context/cost meta, session totals, queued indicator, slash commands, per-message diff,
  project search (`/find` + `/find/file`), offline cache (Room), local notifications (foreground
  watch service, toggle in Servers screen), **OpenCode Go plan usage in Settings**, shortened project
  paths (`ui/common.shortenPath`), chat follow-tail that never yanks you away while reading old
  messages (send always snaps to bottom via `scrollToBottomSignal`), terminal clipboard (paste button
  + long-press copy of visible/whole scrollback, `TerminalEmulator.linesText`), connection status chip
  in the Sessions/Chat app bars (`ConnectionIndicator` + `AppViewModel.connection`, `/global/health`
  every 30 s).
- Chat header shows session totals using the TUI's exact formula (`packages/tui/src/feature-plugins/sidebar/context.tsx`):
  tokens = last assistant message `input + output + reasoning + cache.read + cache.write`;
  % = tokens / model context limit; $ = `session.cost`.
- Remaining ideas: image attachments (image/* file parts), share-session links, LazyColumn line
  virtualization for very large files, WorkManager/notification fallback instead of a foreground service.
- Verified against opencode 1.18.31 live server (sessions, messages, prompt_async, command, diff,
  SSE, file list/content, find/find-file, provider/agent lists).

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:46cd31e7 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/core-concepts/sync-concepts.md for details and anti-patterns.

## Agent Context Profiles

The managed Beads block is task-tracking guidance, not permission to override repository, user, or orchestrator instructions.

- **Conservative (default)**: Use `bd` for task tracking. Do not run git commits, git pushes, or Dolt remote sync unless explicitly asked. At handoff, report changed files, validation, and suggested next commands.
- **Minimal**: Keep tool instruction files as pointers to `bd prime`; use the same conservative git policy unless active instructions say otherwise.
- **Team-maintainer**: Only when the repository explicitly opts in, agents may close beads, run quality gates, commit, and push as part of session close. A current "do not commit" or "do not push" instruction still wins.

## Session Completion

This protocol applies when ending a Beads implementation workflow. It is subordinate to explicit user, repository, and orchestrator instructions.

1. **File issues for remaining work** - Create beads for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **Handle git/sync by active profile**:
   ```bash
   # Conservative/minimal/default: report status and proposed commands; wait for approval.
   git status

   # Team-maintainer opt-in only, unless current instructions forbid it:
   git pull --rebase
   bd dolt push
   git push
   git status
   ```
5. **Hand off** - Summarize changes, validation, issue status, and any blocked sync/commit/push step

**Critical rules:**
- Explicit user or orchestrator instructions override this Beads block.
- Do not commit or push without clear authority from the active profile or the current user request.
- If a required sync or push is blocked, stop and report the exact command and error.
<!-- END BEADS INTEGRATION -->

<!-- BEGIN BEADS CODEX SETUP: generated by bd setup codex -->
## Beads Issue Tracker

Use Beads (`bd`) for durable task tracking in repositories that include it. Use the `beads` skill at `.agents/skills/beads/SKILL.md` (project install) or `~/.agents/skills/beads/SKILL.md` (global install) for Beads workflow guidance, then use the `bd` CLI for issue operations.

### Quick Reference

```bash
bd ready                # Find available work
bd show <id>            # View issue details
bd update <id> --claim  # Claim work
bd close <id>           # Complete work
bd prime                # Refresh Beads context
```

### Rules

- Use `bd` for all task tracking; do not create markdown TODO lists.
- Run `bd prime` when Beads context is missing or stale. Codex 0.129.0+ can load Beads context automatically through native hooks; use `/hooks` to inspect or toggle them.
- Keep persistent project memory in Beads via `bd remember`; do not create ad hoc memory files.

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/core-concepts/sync-concepts.md for details and anti-patterns.
<!-- END BEADS CODEX SETUP -->
