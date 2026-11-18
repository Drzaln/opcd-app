// Format on save: Prettier (the project's own copy and config when present), then `eslint --fix`
// when the project has ESLint. Both run in-process on the text the phone is about to save.
import { createRequire } from "node:module"
import { existsSync } from "node:fs"
import { join, relative, isAbsolute } from "node:path"
import { pathToFileURL } from "node:url"

const ownRequire = createRequire(import.meta.url)

export interface FormatResult {
  text: string
  changed: boolean
  steps: string[]
  warnings: string[]
}

/** Resolves a package from the project first (its version + plugins), then from the helper. */
async function load(name: string, root: string, fallback: boolean): Promise<any | null> {
  try {
    const projectRequire = createRequire(join(root, "package.json"))
    return await import(pathToFileURL(projectRequire.resolve(name)).href)
  } catch {
    if (!fallback) return null
    try { return await import(pathToFileURL(ownRequire.resolve(name)).href) } catch { return null }
  }
}

export function insideRoot(root: string, file: string): boolean {
  const rel = relative(root, file)
  return rel !== "" && !rel.startsWith("..") && !isAbsolute(rel)
}

export async function formatText(root: string, file: string, text: string): Promise<FormatResult> {
  const steps: string[] = []
  const warnings: string[] = []
  let out = text

  const prettierModule = await load("prettier", root, true)
  const prettier = prettierModule?.default ?? prettierModule
  if (prettier?.format) {
    try {
      const info = await prettier.getFileInfo(file, { ignorePath: join(root, ".prettierignore") })
      if (!info.ignored && info.inferredParser) {
        const config = (await prettier.resolveConfig(file, { editorconfig: true })) ?? {}
        out = await prettier.format(out, { ...config, filepath: file })
        steps.push("prettier")
      }
    } catch (e: any) {
      warnings.push(`prettier: ${String(e?.message ?? e).split("\n")[0]}`)
    }
  }

  const hasEslintConfig = ["eslint.config.js", "eslint.config.mjs", "eslint.config.cjs", "eslint.config.ts", ".eslintrc", ".eslintrc.js", ".eslintrc.cjs", ".eslintrc.json", ".eslintrc.yml", ".eslintrc.yaml"]
    .some((f) => existsSync(join(root, f)))
  if (hasEslintConfig && /\.(m|c)?[jt]sx?$/.test(file)) {
    const eslintModule = await load("eslint", root, false)
    const ESLint = eslintModule?.ESLint ?? eslintModule?.default?.ESLint
    if (ESLint) {
      try {
        const eslint = new ESLint({ cwd: root, fix: true })
        if (!(await eslint.isPathIgnored(file))) {
          const [result] = await eslint.lintText(out, { filePath: file })
          if (result?.output != null) out = result.output
          steps.push("eslint --fix")
        }
      } catch (e: any) {
        warnings.push(`eslint: ${String(e?.message ?? e).split("\n")[0]}`)
      }
    }
  }
  return { text: out, changed: out !== text, steps, warnings }
}
