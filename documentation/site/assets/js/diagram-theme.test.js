import assert from "node:assert/strict"
import test from "node:test"

import {
  applyDiagramTheme,
  createDiagramThemeSynchronizer,
  resolveDiagramTheme,
} from "./diagram-theme.js"

test("resolves explicit themes before the system preference", () => {
  assert.equal(resolveDiagramTheme({ dataset: { theme: "light" } }, { matches: true }), "light")
  assert.equal(resolveDiagramTheme({ dataset: { theme: "dark" } }, { matches: false }), "dark")
  assert.equal(resolveDiagramTheme({ dataset: {} }, { matches: true }), "dark")
  assert.equal(resolveDiagramTheme({ dataset: {} }, { matches: false }), "light")
})

test("applies a theme to a loaded SVG document without throwing on inaccessible objects", () => {
  const attributes = new Map()
  const object = {
    contentDocument: {
      documentElement: { setAttribute: (name, value) => attributes.set(name, value) },
    },
  }
  assert.equal(applyDiagramTheme(object, "dark"), true)
  assert.equal(attributes.get("data-theme"), "dark")
  assert.equal(applyDiagramTheme({ get contentDocument() { throw new Error("blocked") } }, "dark"), false)
})

test("synchronizes loaded and late-loading objects across explicit and system changes", () => {
  const attributes = new Map()
  let loadHandler
  let schemeHandler
  const object = {
    contentDocument: {
      documentElement: { setAttribute: (name, value) => attributes.set(name, value) },
    },
    addEventListener(name, handler) {
      if (name === "load") loadHandler = handler
    },
  }
  const document = { querySelectorAll: () => [object] }
  const root = { dataset: {}, ownerDocument: document }
  const colorScheme = {
    matches: true,
    addEventListener(name, handler) {
      if (name === "change") schemeHandler = handler
    },
  }
  const synchronizer = createDiagramThemeSynchronizer(root, colorScheme)

  synchronizer.enhance()
  assert.equal(attributes.get("data-theme"), "dark")
  root.dataset.theme = "light"
  synchronizer.sync()
  assert.equal(attributes.get("data-theme"), "light")
  root.dataset = {}
  colorScheme.matches = false
  schemeHandler()
  assert.equal(attributes.get("data-theme"), "light")
  colorScheme.matches = true
  loadHandler()
  assert.equal(attributes.get("data-theme"), "dark")
})
