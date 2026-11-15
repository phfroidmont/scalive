// Bundles the site's script with the stylesheets, images and licenses that the earlier build
// steps prepared in `build`. Run from the site's root, as `npm run` does.
import { rm, writeFile } from "node:fs/promises"
import { build } from "esbuild"

// A file left by an earlier build would be published by the manifest script.
await rm("dist", { recursive: true, force: true })

// docs:start site-split-build
const result = await build({
  entryPoints: ["assets/js/app.js", "build/*"],
  bundle: true,
  platform: "browser",
  format: "esm",
  splitting: true,
  target: "es2020",
  minify: true,
  loader: { ".svg": "copy", ".txt": "copy" },
  entryNames: "[name]-[hash]",
  chunkNames: "chunks/[name]-[hash]",
  outdir: "dist",
  metafile: true,
})

await writeFile("esbuild-meta.json", JSON.stringify(result.metafile))
// docs:end site-split-build
