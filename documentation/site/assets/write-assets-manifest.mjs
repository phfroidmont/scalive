// Writes the deployment manifest that Scalive loads at startup. It lists every file the site
// serves: the bundler's outputs from its metafile, then the files the other build steps emit.
import { readdir, readFile, writeFile } from "node:fs/promises"
import path from "node:path"
import { fileURLToPath } from "node:url"

const siteRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
const outputRoot = path.join(siteRoot, "dist")

// The bundler's entry points, and the stable alias the root layout asks for each one by.
const entryAliases = {
  "assets/js/app.js": "app.js",
}

// Served from the same classpath prefix, but written there by the documentation pipeline.
const generatedElsewhere = ["search-index.json"]

const assets = {}

// esbuild put a content hash in the name of each file it emitted, so each is immutable under
// that name. Chunks have an `entryPoint` in the metafile too, so only the entry points listed
// above get an alias; every other output is listed under its final path.
const metafile = JSON.parse(await readFile(path.join(siteRoot, "esbuild-meta.json"), "utf8"))
for (const [output, { entryPoint }] of Object.entries(metafile.outputs)) {
  const file = path.relative(outputRoot, path.join(siteRoot, output))
  assets[entryAliases[entryPoint] ?? file] = { file, cache: "immutable" }
}

const missingEntries = Object.values(entryAliases).filter((alias) => !(alias in assets))
if (missingEntries.length > 0) {
  throw new Error(`esbuild's metafile has no output for: ${missingEntries.join(", ")}`)
}

// The remaining files (styles, fonts, images, the search index) keep their plain names, which
// the diagrams rely on to import `fonts.css`. A plain name can serve new bytes after a
// deployment, so browsers must revalidate it.
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
