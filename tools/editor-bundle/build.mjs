// Bundles editor.ts into app/src/main/assets/editor/editor.js and regenerates NOTICE.txt.
// Run: cd tools/editor-bundle && npm ci && npm run build
import { build } from "esbuild"
import { createHash } from "node:crypto"
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"

const here = dirname(fileURLToPath(import.meta.url))
const out = join(here, "../../app/src/main/assets/editor")
mkdirSync(out, { recursive: true })

const result = await build({
  entryPoints: [join(here, "editor.ts")],
  bundle: true,
  minify: true,
  format: "iife",
  // Lowered to Chromium 80 (the same WebView floor as the xterm terminal, MIN_WEBVIEW_CHROME).
  target: ["chrome80"],
  legalComments: "none",
  metafile: true,
  write: false,
})
const js = result.outputFiles[0].text
writeFileSync(join(out, "editor.js"), js)

// NOTICE: every bundled package with its version and license text.
const inputs = Object.keys(result.metafile.inputs)
const packages = new Set()
for (const input of inputs) {
  const m = /node_modules\/((?:@[^/]+\/)?[^/]+)\//.exec(input)
  if (m) packages.add(m[1])
}
let notice = "Bundled third-party code for the CodeMirror 6 editor (app/src/main/assets/editor/editor.js).\n"
notice += "Built from tools/editor-bundle (npm ci && npm run build) with esbuild.\n"
notice += `sha256 editor.js = ${createHash("sha256").update(js).digest("hex")}\n`
for (const name of [...packages].sort()) {
  const dir = join(here, "node_modules", name)
  const pkg = JSON.parse(readFileSync(join(dir, "package.json"), "utf8"))
  notice += `\n=== ${name} ${pkg.version} (${pkg.license}) ===\n`
  const licenseFile = ["LICENSE", "LICENSE.md", "LICENSE.txt"].map((f) => join(dir, f)).find(existsSync)
  notice += licenseFile ? readFileSync(licenseFile, "utf8").trim() + "\n" : `License: ${pkg.license}\n`
}
writeFileSync(join(out, "NOTICE.txt"), notice)
console.log(`editor.js ${(js.length / 1024).toFixed(0)} KB, ${packages.size} packages`)
