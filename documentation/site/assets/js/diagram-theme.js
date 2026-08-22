const diagramSelector = ".docs-diagram object[type='image/svg+xml']"

export function resolveDiagramTheme(root, colorScheme) {
  const selected = root?.dataset?.theme
  if (selected === "light" || selected === "dark") return selected
  return colorScheme?.matches ? "dark" : "light"
}

export function applyDiagramTheme(object, theme) {
  try {
    const svg = object.contentDocument?.documentElement
    if (!svg) return false
    svg.setAttribute("data-theme", theme)
    return true
  } catch (_error) {
    return false
  }
}

export function createDiagramThemeSynchronizer(
  root = globalThis.document?.documentElement,
  colorScheme = globalThis.matchMedia?.("(prefers-color-scheme: dark)"),
) {
  const document = root?.ownerDocument
  const observed = new WeakSet()

  const apply = (object) => applyDiagramTheme(object, resolveDiagramTheme(root, colorScheme))
  const enhance = (scope = document) => {
    scope?.querySelectorAll(diagramSelector).forEach((object) => {
      if (!observed.has(object)) {
        observed.add(object)
        object.addEventListener("load", () => apply(object))
      }
      apply(object)
    })
  }
  const sync = () => enhance(document)
  const handleColorSchemeChange = () => {
    if (root?.dataset?.theme !== "light" && root?.dataset?.theme !== "dark") sync()
  }

  colorScheme?.addEventListener?.("change", handleColorSchemeChange)
  return { enhance, sync }
}
