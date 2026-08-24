import { copyFile, mkdir, readFile, writeFile } from "node:fs/promises"
import path from "node:path"
import { fileURLToPath } from "node:url"

const assetsRoot = path.dirname(fileURLToPath(import.meta.url))
const siteRoot = path.dirname(assetsRoot)
const modulesRoot = path.join(siteRoot, "node_modules", "@fontsource-variable")
const outputRoot = path.join(siteRoot, "dist")

const fonts = [
  {
    packageName: "instrument-sans",
    family: "Instrument Sans Variable",
    weight: "400 700",
    file: "instrument-sans-latin-wght-normal.woff2",
    license: "instrument-sans-OFL.txt",
  },
  {
    packageName: "jetbrains-mono",
    family: "JetBrains Mono Variable",
    weight: "100 800",
    file: "jetbrains-mono-latin-wght-normal.woff2",
    license: "jetbrains-mono-OFL.txt",
  },
]

await mkdir(outputRoot, { recursive: true })

const declarations = await Promise.all(fonts.map(async (font) => {
  const packageRoot = path.join(modulesRoot, font.packageName)
  const [contents, unicode] = await Promise.all([
    readFile(path.join(packageRoot, "files", font.file)),
    readFile(path.join(packageRoot, "unicode.json"), "utf8").then(JSON.parse),
  ])

  await copyFile(path.join(packageRoot, "LICENSE"), path.join(outputRoot, font.license))

  return `@font-face {
  font-family: "${font.family}";
  font-style: normal;
  font-display: swap;
  font-weight: ${font.weight};
  src: url("data:font/woff2;base64,${contents.toString("base64")}") format("woff2-variations");
  unicode-range: ${unicode.latin};
}`
}))

await writeFile(path.join(outputRoot, "fonts.css"), `${declarations.join("\n\n")}\n`)
await copyFile(path.join(assetsRoot, "brand", "scalive-mark.svg"), path.join(outputRoot, "favicon.svg"))
await Promise.all([
  "runtime-connected-turn.svg",
  "runtime-connected-lifetime.svg",
  "runtime-disconnected-lifetime.svg",
].map((asset) => copyFile(path.join(assetsRoot, "diagrams", asset), path.join(outputRoot, asset))))
