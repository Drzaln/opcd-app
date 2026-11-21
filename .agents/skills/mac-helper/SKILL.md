---
name: mac-helper
description: Work on OpenCode Mobile's optional Mac helper (helper/, Node + TypeScript) — TypeScript language server relay, format on save, Metro, adb/Android builds, emulators, iOS simulators, live device screen — and its app side (HelperClient, Settings → Mac helper, Code panels).
---

# Work on the Mac helper

The helper is optional: the app must keep working without it (features just hide). It's a small Node
daemon on the Mac that the phone talks to over one WebSocket.

- Code: `helper/src/` — `main.ts` (entry), `server.ts` (HTTP + WebSocket, message switch), `auth.ts`
  (pairing + bearer tokens), `confirm.ts` (asks the person at the Mac to approve a pairing:
  macOS dialog, terminal y/N fallback), `lsp.ts`, `format.ts`, `metro.ts`, `adb.ts`, `emu.ts`, `screen.ts`
  (scrcpy relay), `sim.ts` / `simscreen.ts` (iOS simulators).
- Protocol and security notes: `helper/README.md` ("Protocol (v1)", "Security"). Keep it in sync.
- App side: `data/net/HelperClient.kt` (`HelperManager`, owned by `OpenCodeApp`), Settings → Mac
  helper (pairing), `ui/editor/Metro.kt`, `Android.kt`, `Screen.kt` (`ScreenController`, MediaCodec
  decoder, `DeviceScreen`; a lighter stream on metered networks), LSP wiring in `EditorViewModel`.

## Run and test

```bash
cd helper
npm ci
npm start          # builds dist/helper.mjs and listens on :4097 (127.0.0.1 and the Tailscale IP)
npm test           # node --test over test/*.test.mjs
```

From an emulator or USB phone: `adb reverse tcp:4097 tcp:4097`, then Settings → Mac helper → Pair in
the app. Pairing pops an Allow/Deny dialog on the Mac — that's the user's to answer
(`OC_HELPER_AUTO_APPROVE=1` only works for loopback-only test runs).

## Rules

- **Pinned dependencies** (`package.json` exact versions); don't bump as a side effect.
- New capability = a message type in `server.ts` + an entry in `features()` (served by
  `GET /helper/info`; the app only shows a feature the helper lists), + a README protocol entry,
  + a test.
- The helper runs commands on the user's Mac: validate paths and arguments, never build shell strings
  from phone input, and only accept requests from paired devices (bearer token).
- Device screen and emulator features need scrcpy / adb / Android emulator (and Xcode + idb-companion
  for iOS) on the Mac; detect and report missing tools instead of failing silently.
- Don't install tools on the user's Mac without asking.
