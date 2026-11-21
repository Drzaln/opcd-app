---
name: editor-bundle
description: Change OpenCode Mobile's code editor — the CodeMirror 6 bundle in tools/editor-bundle (keymaps, languages, theme, LSP glue) and its bridge to Kotlin (EditorWebView/EditorViewModel). Use when touching editor.ts, editor.html or editor shortcuts.
---

# Change the CodeMirror editor

The editor is CodeMirror 6 running in a WebView. The JS is **built outside Gradle** and the output is
committed.

- Source: `tools/editor-bundle/editor.ts` (+ exact versions in `package.json` / `package-lock.json`).
- Output (committed): `app/src/main/assets/editor/editor.js` and `NOTICE.txt` (package licenses + the
  bundle's SHA-256). The page is `app/src/main/assets/editor/editor.html`.
- Kotlin side: `ui/editor/EditorWebView.kt` (creates the WebView, `EditorBridge` = the
  `AndroidEditor` JS interface), `ui/editor/EditorViewModel.kt` (tabs, saves, conflicts, LSP, layout
  commands).

## Rebuild

```bash
cd tools/editor-bundle && npm ci && npm run build      # writes editor.js + NOTICE.txt
```

Commit `editor.ts`, `editor.js` and `NOTICE.txt` together. Don't change dependency versions unless
that's the task (the build targets Chromium 80, the app's WebView floor `MIN_WEBVIEW_CHROME`).

## Adding a shortcut or command

1. In `editor.ts`, add a keymap entry next to `Mod-s` / `Mod-p`:
   `{ key: "Mod-x", preventDefault: true, run: () => { bridge.onCommand("myCommand"); return true } }`.
2. In `EditorBridge.onCommand` (EditorWebView.kt), route the name to the ViewModel. Bridge calls arrive
   on a WebView thread: post to the main thread or emit into a flow (see `layoutCommand` /
   `layoutCommands`).
3. If the shortcut should also work when the editor isn't focused, handle it in Compose too
   (`onPreviewKeyEvent` in `CodeScreen.WideCode`).
4. Rebuild, install, and test with a hardware key combo (`adb shell input keycombination 113 <keycode>`)
   after tapping into the editor.

## Gotchas

- Keep `html, body` in `editor.html` **transparent** and the WebView `INVISIBLE` until `onReady`: a
  WebView's first frame can paint outside its bounds, and a dark page background flashed over the
  whole screen in light mode.
- Theme colours come from the app (`--oc-*` CSS variables set from `OcTheme.colors`); don't hard-code
  colours in the page.
- Saves are SHA-256 verified and conflict-checked; never write a file without the hash check.
- Typing performance on very large files hasn't been verified — be careful with per-keystroke work.
