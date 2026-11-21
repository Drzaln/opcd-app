---
name: ship-release
description: Release OpenCode Mobile when the user says "ship it", "release it" or "push it" — update docs, run make ship (version bump, release build, push main, tag v*), and report without polling GitHub.
---

# Ship a release

Only when the user asks to ship/release/push. Never bump or tag otherwise.

1. **Commit the work first** if the user asked to commit (separate commit from the release commit).
   `scripts/ship.sh` runs `git add -A`, so make sure nothing unwanted is in the tree: `git status`
   (`keystore.jks` / `keystore.properties` are gitignored and must stay that way; no `.kotlin/`, logs,
   APKs, screenshots).
2. **Docs must already be current** (ship.sh only stamps the version):
   - `PROGRESS.md` — what changed, gotchas, what was verified / not verified (with the bead id).
   - `README.md` — user-facing features, setup, usage.
   - `AGENTS.md` — architecture, conventions, gotchas (CLAUDE.md imports it; don't duplicate).
3. Tests: `./gradlew :app:testDebugUnitTest` (and `cd helper && npm test` if `helper/` changed).
4. `make ship` (patch) or `./scripts/ship.sh minor|major|x.y.z` if the user asked for a bump type.
   It bumps `versionCode`/`versionName` in `app/build.gradle.kts`, stamps `PROGRESS.md`, builds
   `assembleRelease`, commits "Release vX.Y.Z (versionCode N)", pushes `main`, tags and pushes `vX.Y.Z`.
5. The tag triggers `.github/workflows/build.yml`, which builds the APK and creates the GitHub Release.
   **Don't poll the GitHub API or Actions** (rate limits; the user asked not to). Report the version,
   commits and tag, and tell the user to check the Actions/Releases tab.
6. If the codebase-memory MCP server is available, re-index the repo afterwards.
7. If the CI release step returns 403, the repo's workflow permissions are read-only: ask the user to
   enable Settings → Actions → General → Workflow permissions → "Read and write".

The in-app updater downloads `releases/latest/download/app-release.apk`; it only installs over an app
signed with the same key (CI uses the real key from secrets, local builds fall back to the debug key).
