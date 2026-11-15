// Writes the deployment manifest that Scalive loads at startup. It lists every file the site
// serves: the bundler's outputs from its metafile, then the few files it did not emit.
import { readdir, readFile, writeFile } from "node:fs/promises"
import path from "node:path"
import { fileURLToPath } from "node:url"

const siteRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
const outputRoot = path.join(siteRoot, "dist")

// The stable alias the site asks for an entry point by: the script's own name, and the name of
// each stylesheet, image and license the earlier build steps prepared in `build`.
function entryAlias(entryPoint) {
  if (entryPoint === "assets/js/app.js") return "app.js"
  if (entryPoint?.startsWith("build/")) return entryPoint.slice("build/".length)
  return undefined
}

// Served from the same classpath prefix, but written there by the documentation pipeline.
const generatedElsewhere = ["search-index.json"]

const assets = {}

// esbuild put a content hash in the name of each file it emitted, so each is immutable under
// that name. Chunks have an `entryPoint` in the metafile too, so only the entry points named
// above get an alias; every other output is listed under its final path.
const metafile = JSON.parse(await readFile(path.join(siteRoot, "esbuild-meta.json"), "utf8"))
for (const [output, { entryPoint }] of Object.entries(metafile.outputs)) {
  const file = path.relative(outputRoot, path.join(siteRoot, output))
  assets[entryAlias(entryPoint) ?? file] = { file, cache: "immutable" }
}

// The diagram SVGs import their fonts by name, and esbuild cannot rewrite a reference inside an
// SVG. That name is a one-line stylesheet importing the hashed one.
await writeFile(
  path.join(outputRoot, "diagram-fonts.css"),
  `@import url("${assets["fonts.css"].file}");\n`,
)

// The files esbuild did not emit keep their plain names. A plain name can serve new bytes after
// a deployment, so browsers must revalidate it.
const bundled = new Set(Object.values(assets).map((asset) => asset.file))
const emitted = (await readdir(outputRoot, { recursive: true, withFileTypes: true }))
  .filter((entry) => entry.isFile())
  .map((entry) => path.relative(outputRoot, path.join(entry.parentPath, entry.name)))
for (const file of [...emitted, ...generatedElsewhere]) {
  if (!bundled.has(file)) assets[file] = { file, cache: "revalidate" }
}

const sorted = Object.fromEntries(Object.entries(assets).sort(([a], [b]) => a.localeCompare(b)))
await writeFile(
  path.join(outputRoot, "assets-manifest.json"),
  `${JSON.stringify({ version: 1, assets: sorted }, null, 2)}\n`,
)
