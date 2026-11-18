// CodeMirror 6 editor for OpenCode Mobile. Built by build.mjs into app/src/main/assets/editor/editor.js
// and inlined into editor.html by EditorWebView.kt (same pattern as the xterm.js terminal).
//
// Kotlin -> JS: window.__oc* functions below.
// JS -> Kotlin: window.AndroidEditor (a @JavascriptInterface bridge).

import { EditorState, Compartment, Prec, Text, type Extension } from "@codemirror/state"
import {
  EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter,
  drawSelection, highlightSpecialChars, rectangularSelection, dropCursor,
} from "@codemirror/view"
import {
  defaultKeymap, history, historyKeymap, indentWithTab, undo, redo, indentMore, indentLess,
  toggleComment, insertTab, cursorCharLeft, cursorCharRight, selectAll,
} from "@codemirror/commands"
import {
  bracketMatching, indentOnInput, indentUnit, syntaxHighlighting, HighlightStyle,
  StreamLanguage, foldGutter, foldKeymap,
} from "@codemirror/language"
import { search, searchKeymap, openSearchPanel, gotoLine, highlightSelectionMatches } from "@codemirror/search"
import { closeBrackets, closeBracketsKeymap, insertBracket } from "@codemirror/autocomplete"
import { javascript } from "@codemirror/lang-javascript"
import { json } from "@codemirror/lang-json"
import { markdown } from "@codemirror/lang-markdown"
import { css } from "@codemirror/lang-css"
import { yaml } from "@codemirror/lang-yaml"
import { xml } from "@codemirror/lang-xml"
import { kotlin } from "@codemirror/legacy-modes/mode/clike"
import { tags as t } from "@lezer/highlight"

interface Bridge {
  onReady(): void
  onChange(path: string, dirty: boolean, hash: string): void
  onSaveRequested(path: string): void
  onCursor(line: number, col: number): void
  onCommand(name: string): void
  onText(id: number, path: string, text: string): void
  onSnapshot(path: string, text: string): void
  onError(message: string): void
  onFocus(focused: boolean): void
}

declare global {
  interface Window {
    AndroidEditor?: Bridge
    [key: string]: unknown
  }
}

const bridge: Bridge = window.AndroidEditor ?? {
  onReady() {}, onChange() {}, onSaveRequested() {}, onCursor() {}, onCommand() {},
  onText() {}, onSnapshot() {}, onError(m: string) { console.error(m) }, onFocus() {},
}

function languageFor(name: string): Extension {
  switch (name) {
    case "typescript": return javascript({ typescript: true })
    case "tsx": return javascript({ typescript: true, jsx: true })
    case "javascript": return javascript()
    case "jsx": return javascript({ jsx: true })
    case "json": return json()
    case "markdown": return markdown()
    case "css": return css()
    case "yaml": return yaml()
    case "xml": return xml()
    case "kotlin": return StreamLanguage.define(kotlin)
    default: return []
  }
}

// Colors come from CSS variables that Kotlin sets from OcTheme (see __ocSetTheme), so a theme
// switch is just a variable update — no editor reconfiguration.
const v = (name: string) => `var(--oc-${name})`

const highlight = HighlightStyle.define([
  { tag: [t.keyword, t.modifier, t.controlKeyword, t.operatorKeyword, t.definitionKeyword, t.moduleKeyword], color: v("kw") },
  { tag: [t.string, t.special(t.string), t.regexp, t.character], color: v("str") },
  { tag: [t.number, t.bool, t.null, t.atom], color: v("num") },
  { tag: [t.propertyName, t.attributeName], color: v("prop") },
  { tag: [t.function(t.variableName), t.function(t.propertyName), t.macroName], color: v("fn") },
  { tag: [t.typeName, t.className, t.namespace, t.standard(t.typeName)], color: v("type") },
  { tag: [t.tagName, t.angleBracket], color: v("tag") },
  { tag: [t.comment, t.lineComment, t.blockComment, t.docComment], color: v("comment"), fontStyle: "italic" },
  { tag: [t.meta, t.processingInstruction], color: v("muted") },
  { tag: t.heading, color: v("fn"), fontWeight: "bold" },
  { tag: t.link, color: v("link"), textDecoration: "underline" },
  { tag: t.emphasis, fontStyle: "italic" },
  { tag: t.strong, fontWeight: "bold" },
  { tag: t.invalid, color: v("error") },
])

const theme = EditorView.theme({
  "&": { height: "100%", backgroundColor: v("bg"), color: v("text") },
  ".cm-scroller": { fontFamily: "OcMono, monospace", lineHeight: "1.7", overscrollBehavior: "contain" },
  ".cm-content": { caretColor: v("link"), paddingBottom: "40vh" },
  ".cm-cursor, .cm-dropCursor": { borderLeftColor: v("link"), borderLeftWidth: "2px" },
  "&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection": { backgroundColor: v("selection") },
  ".cm-activeLine": { backgroundColor: v("line") },
  ".cm-gutters": { backgroundColor: v("bg"), color: v("lineno"), border: "none" },
  ".cm-activeLineGutter": { backgroundColor: v("line"), color: v("text") },
  ".cm-lineNumbers .cm-gutterElement": { padding: "0 10px 0 8px", minWidth: "32px" },
  ".cm-foldGutter .cm-gutterElement": { color: v("lineno") },
  "&.cm-focused .cm-matchingBracket": { backgroundColor: v("accentSoft"), outline: `1px solid ${v("border")}` },
  ".cm-searchMatch": { backgroundColor: v("accentSoft"), outline: `1px solid ${v("link")}` },
  ".cm-searchMatch.cm-searchMatch-selected": { backgroundColor: v("selection") },
  ".cm-selectionMatch": { backgroundColor: v("accentSoft") },
  ".cm-panels": { backgroundColor: v("surface"), color: v("text"), borderColor: v("border") },
  ".cm-panels.cm-panels-top": { borderBottom: `1px solid ${v("border")}` },
  ".cm-panels.cm-panels-bottom": { borderTop: `1px solid ${v("border")}` },
  ".cm-panel": { fontFamily: "system-ui, sans-serif", fontSize: "14px", padding: "6px 8px" },
  ".cm-panel input[type=text], .cm-textfield": {
    backgroundColor: v("bg"), color: v("text"), border: `1px solid ${v("border")}`, borderRadius: "8px",
    padding: "8px 10px", fontSize: "15px", minHeight: "40px", boxSizing: "border-box",
  },
  ".cm-panel button, .cm-button": {
    backgroundColor: v("surface"), backgroundImage: "none", color: v("text"), border: `1px solid ${v("border")}`,
    borderRadius: "10px", padding: "8px 12px", minHeight: "40px", fontSize: "14px", margin: "3px",
  },
  ".cm-panel label": { fontSize: "13px", color: v("muted"), margin: "3px 6px", display: "inline-flex", alignItems: "center" },
  ".cm-panel.cm-search [name=close]": { fontSize: "22px", padding: "4px 10px", color: v("muted") },
  ".cm-tooltip": { backgroundColor: v("popup"), border: `1px solid ${v("popupBorder")}`, color: v("text"), boxShadow: "0 6px 18px rgba(0,0,0,.35)" },
  ".cm-foldPlaceholder": { backgroundColor: v("accentSoft"), border: "none", color: v("link") },
}, { dark: true })

const wrap = new Compartment()
const language = new Compartment()
const indent = new Compartment()
const tabSize = new Compartment()
let wrapOn = false

interface Tab {
  path: string
  state: EditorState
  saved: Text
  pending: Map<number, Text>
}

const PAIRS = ["{}", "()", "[]", "<>", "''", '""']
const tabs = new Map<string, Tab>()
let current: string | null = null
let changeTimer: number | undefined
let snapshotTimer: number | undefined

function detectIndent(text: string): string {
  let tabsCount = 0, two = 0, four = 0
  const lines = text.split("\n", 2000)
  for (const line of lines) {
    if (line.startsWith("\t")) tabsCount++
    else {
      const m = /^( +)\S/.exec(line)
      if (m) { if (m[1].length % 4 === 0) four++; else if (m[1].length % 2 === 0) two++ }
    }
  }
  if (tabsCount > two + four) return "\t"
  return two >= four / 2 && two > 0 ? "  " : four > 0 ? "    " : "  "
}

function extensions(lang: string, text: string): Extension[] {
  const unit = detectIndent(text)
  return [
    lineNumbers(),
    highlightActiveLineGutter(),
    highlightSpecialChars(),
    history(),
    foldGutter(),
    drawSelection(),
    dropCursor(),
    EditorState.allowMultipleSelections.of(true),
    indentOnInput(),
    syntaxHighlighting(highlight),
    bracketMatching(),
    closeBrackets(),
    rectangularSelection(),
    highlightActiveLine(),
    highlightSelectionMatches(),
    search({ top: true }),
    text.includes("\r\n") ? EditorState.lineSeparator.of("\r\n") : [],
    indent.of(indentUnit.of(unit)),
    tabSize.of(EditorState.tabSize.of(unit === "\t" ? 4 : unit.length)),
    language.of(languageFor(lang)),
    wrap.of(wrapOn ? EditorView.lineWrapping : []),
    theme,
    Prec.highest(keymap.of([
      { key: "Mod-s", preventDefault: true, run: () => { if (current) bridge.onSaveRequested(current); return true } },
      { key: "Mod-p", preventDefault: true, run: () => { bridge.onCommand("palette"); return true } },
      { key: "Mod-g", preventDefault: true, run: gotoLine },
      { key: "Mod-w", preventDefault: true, run: () => { bridge.onCommand("closeTab"); return true } },
    ])),
    keymap.of([
      ...closeBracketsKeymap,
      ...defaultKeymap,
      ...searchKeymap,
      ...historyKeymap,
      ...foldKeymap,
      indentWithTab,
    ]),
    EditorView.domEventHandlers({
      focus: () => { bridge.onFocus(true) },
      blur: () => { bridge.onFocus(false) },
    }),
    EditorView.updateListener.of((u) => {
      if (u.docChanged) scheduleChange()
      if (u.selectionSet || u.docChanged) {
        const head = u.state.selection.main.head
        const line = u.state.doc.lineAt(head)
        bridge.onCursor(line.number, head - line.from + 1)
      }
    }),
  ]
}

const view = new EditorView({
  state: EditorState.create({ doc: "", extensions: extensions("plain", "") }),
  parent: document.getElementById("editor")!,
})

function decode(b64: string): string {
  const bin = atob(b64)
  const bytes = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
  return new TextDecoder("utf-8").decode(bytes)
}

async function sha256(text: string): Promise<string> {
  try {
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text))
    return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("")
  } catch (_) {
    return ""
  }
}

function saveCurrent() {
  if (current) {
    const tab = tabs.get(current)
    if (tab) tab.state = view.state
  }
}

function scheduleChange() {
  clearTimeout(changeTimer)
  clearTimeout(snapshotTimer)
  const path = current
  if (!path) return
  changeTimer = window.setTimeout(async () => {
    const tab = tabs.get(path)
    if (!tab) return
    const state = path === current ? view.state : tab.state
    const dirty = !state.doc.eq(tab.saved)
    bridge.onChange(path, dirty, await sha256(state.sliceDoc()))
  }, 250)
  // A snapshot lets Kotlin restore unsaved text if the WebView is torn down (navigation away,
  // process pressure). Not on every keystroke — large files make this a multi-MB string.
  snapshotTimer = window.setTimeout(() => {
    const tab = tabs.get(path)
    if (!tab) return
    const state = path === current ? view.state : tab.state
    if (!state.doc.eq(tab.saved)) bridge.onSnapshot(path, state.sliceDoc())
  }, 1500)
}

function gotoLineNumber(line: number) {
  if (!line || line < 1) return
  const doc = view.state.doc
  const target = doc.line(Math.min(line, doc.lines))
  view.dispatch({ selection: { anchor: target.from }, effects: EditorView.scrollIntoView(target.from, { y: "center" }) })
}

const api: Record<string, (...args: any[]) => unknown> = {
  // Opens (or replaces) a tab. `savedB64` is the on-disk text when restoring an unsaved snapshot.
  __ocLoad(path: string, b64: string, lang: string, line: number, savedB64?: string) {
    saveCurrent()
    const text = decode(b64)
    const saved = savedB64 != null ? decode(savedB64) : text
    const state = EditorState.create({ doc: text, extensions: extensions(lang, saved) })
    tabs.set(path, { path, state, saved: state.toText(saved), pending: new Map() })
    current = path
    view.setState(state)
    gotoLineNumber(line)
    if (savedB64 != null) scheduleChange()
  },
  __ocShow(path: string, line: number) {
    const tab = tabs.get(path)
    if (!tab) return false
    if (current !== path) {
      saveCurrent()
      current = path
      view.setState(tab.state)
    }
    gotoLineNumber(line)
    return true
  },
  __ocHas(path: string) {
    return tabs.has(path)
  },
  __ocClose(path: string) {
    tabs.delete(path)
    if (current === path) {
      current = null
      view.setState(EditorState.create({ doc: "", extensions: extensions("plain", "") }))
    }
  },
  __ocGetText(id: number, path: string) {
    const tab = tabs.get(path)
    if (!tab) { bridge.onError(`no tab for ${path}`); return }
    const state = path === current ? view.state : tab.state
    tab.pending.set(id, state.doc)
    bridge.onText(id, path, state.sliceDoc())
  },
  // The text sent with request `id` is now on disk.
  __ocSaved(id: number, path: string) {
    const tab = tabs.get(path)
    const doc = tab?.pending.get(id)
    if (!tab || !doc) return
    tab.pending.delete(id)
    tab.saved = doc
    scheduleChange()
  },
  __ocSetTheme(json: string) {
    const tokens = JSON.parse(json) as Record<string, string>
    const root = document.documentElement.style
    for (const [k, val] of Object.entries(tokens)) root.setProperty(`--oc-${k}`, val)
    document.body.style.backgroundColor = tokens.bg ?? ""
  },
  __ocSetFontSize(px: number) {
    document.documentElement.style.setProperty("--oc-font-size", `${px}px`)
    view.requestMeasure()
  },
  __ocSetWrap(on: boolean) {
    wrapOn = !!on
    view.dispatch({ effects: wrap.reconfigure(wrapOn ? EditorView.lineWrapping : []) })
    for (const tab of tabs.values()) {
      if (tab.path !== current) tab.state = tab.state.update({ effects: wrap.reconfigure(wrapOn ? EditorView.lineWrapping : []) }).state
    }
  },
  // Accessory key row input. Brackets/quotes go through closeBrackets so they pair like typed keys.
  __ocInsert(b64: string) {
    const text = decode(b64)
    if (text.length === 1 || (text.length === 2 && PAIRS.includes(text))) {
      const tr = insertBracket(view.state, text[0])
      if (tr) { view.dispatch(tr); return }
    }
    if (text.length === 2 && PAIRS.includes(text)) {
      // Not auto-closed (e.g. "<>" outside JSX): insert both and park the cursor between them.
      const { from, to } = view.state.selection.main
      view.dispatch({ changes: { from, to, insert: text }, selection: { anchor: from + 1 }, scrollIntoView: true, userEvent: "input.type" })
      return
    }
    view.dispatch(view.state.replaceSelection(text), { scrollIntoView: true, userEvent: "input.type" })
  },
  __ocCommand(name: string) {
    const commands: Record<string, (v: EditorView) => boolean> = {
      undo, redo, indentMore, indentLess, toggleComment, insertTab, selectAll,
      left: cursorCharLeft, right: cursorCharRight,
      find: openSearchPanel, goToLine: gotoLine,
    }
    const run = commands[name]
    if (run) run(view)
    view.focus()
  },
  __ocFocus() {
    view.focus()
  },
}
Object.assign(window, api)

window.addEventListener("error", (e) => bridge.onError(`${e.message} (${e.filename}:${e.lineno})`))
window.addEventListener("unhandledrejection", (e) => bridge.onError(`unhandled: ${String((e as PromiseRejectionEvent).reason)}`))
console.log(`OC editor ready ${window.innerWidth}x${window.innerHeight}`)
bridge.onReady()
