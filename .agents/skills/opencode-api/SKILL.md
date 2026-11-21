---
name: opencode-api
description: Add or change how OpenCode Mobile talks to the opencode server (HTTP routes, DTOs, live SSE events) so it keeps working on both opencode 1.x and 2.x. Use for any new endpoint, changed payload, or chat/streaming behavior.
---

# Talk to the opencode server (1.x and 2.x)

The app supports **both** API generations at runtime; never drop one. Pinned versions in the repo
matter — don't upgrade dependencies to make an API change work.

## Where things are

- `data/net/OpenCodeApi.kt` — the app-facing client. `detect()` probes `/api/info` (2.x answers JSON,
  1.x 404s) and caches the result per server. Each public method calls either `OpenCodeV1Api` or
  `OpenCodeV2Api` and returns the app's DTOs (`data/model/Dtos.kt`). 2.x payloads are mapped with the
  private `map*` functions (`mapSession`, `mapMessage`, `mapContent`, …).
- `data/net/OpenCodeV1Api.kt` — Retrofit, 1.x routes (`/session`, `/file`, `/event`, …).
- `data/net/OpenCodeV2Api.kt` — Retrofit, 2.x `/api/*` routes returning raw `JsonElement`.
- `data/net/OpenCodeRepository.kt` — API instances, SSE with reconnect (`ReconnectingListener`).
  1.x events arrive ready to use; 2.x events go through `V2LiveEvents.map(type, created, data)`
  first and fall back to `mapV2Event`.
- `data/net/V2LiveEvents.kt` — turns 2.x `session.*` events (text/reasoning deltas, tool
  input/called/success/failed, step started/ended) into the app's `message.updated` /
  `message.part.updated {part, delta}` events. Unit-tested in `V2LiveEventsTest`.

## Recipe

1. **Look at the real payloads** on both versions before writing code. Start a test server
   (`make serve`, password `secret`) and curl it:
   ```bash
   curl -s -u opencode:secret http://127.0.0.1:4199/api/info          # which version
   curl -s -u opencode:secret "http://127.0.0.1:4199/session?directory=$PWD"
   curl -sN -u opencode:secret http://127.0.0.1:4199/api/event         # 2.x live events
   ```
   If only one version is installed, say which one you verified and which you didn't. Ground truth for
   shapes is the opencode repo's generated SDK types.
2. Add the route to `OpenCodeV1Api` and/or `OpenCodeV2Api`, then one method in `OpenCodeApi` that
   branches on the detected protocol and returns an app DTO. Keep DTO fields nullable/defaulted
   (`ignoreUnknownKeys = true` is on).
3. **Instance routing:** most calls must carry the project folder — 1.x `?directory=`, 2.x
   `?directory=` for sessions and `?location[directory]=` for most instance routes. Missing it gives
   you the server's default folder, silently.
4. Live events: the SSE stream is folder-scoped and carries **every session in that folder** — filter
   by `sessionID` (`ChatViewModel.isOwnSession`). Event type is in the JSON body, not the SSE `event:`
   field. `server.heartbeat` doesn't prove the stream is delivering.
5. Never treat a **network** error as "this is a 1.x server" in detection (it would pin the wrong
   protocol until the app restarts).
6. Add a unit test for any mapping (see `V2LiveEventsTest`, `RepositoryCacheTest`). `OC_IT=1
   ./gradlew :app:testDebugUnitTest` also runs the live integration tests against `make serve`.
7. Check it in the app (`verify-on-device` skill) and note the verified server versions in
   `PROGRESS.md` ("Server integration" facts if you learned something non-obvious).

## Handy facts

- Auth: HTTP Basic, user `opencode` + the server password; 401 otherwise.
- `/session/{id}/diff` returns whole before/after file contents; the app computes line diffs.
- Editor saves: 2.2+ has `POST /api/experimental/fs/write`; older servers use the atomic base64 writer
  over a one-shot PTY (`FileWriter`, `PtyOneShot`). Git also runs through `PtyOneShot` (`GitRunner`).
- The 2.x server gzips responses; keep list fetches small (the chat fetches a tail, not the history).
