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

test("synchronizes a loaded object across explicit and system changes", () => {
  const attributes = new Map()
  let schemeHandler
  const object = {
    contentDocument: {
      documentElement: { setAttribute: (name, value) => attributes.set(name, value) },
    },
    addEventListener() {},
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
  colorScheme.matches = false
  schemeHandler()
  assert.equal(attributes.get("data-theme"), "light")
  root.dataset = {}
  colorScheme.matches = true
  schemeHandler()
  assert.equal(attributes.get("data-theme"), "dark")
})

test("applies the current theme when an object finishes loading", () => {
  const attributes = new Map()
  let loadHandler
  const object = {
    contentDocument: null,
    addEventListener(name, handler) {
      if (name === "load") loadHandler = handler
    },
  }
  const root = {
    dataset: { theme: "light" },
    ownerDocument: { querySelectorAll: () => [object] },
  }
  const synchronizer = createDiagramThemeSynchronizer(root, { matches: true })

  synchronizer.enhance()
  object.contentDocument = {
    documentElement: { setAttribute: (name, value) => attributes.set(name, value) },
  }
  loadHandler()

  assert.equal(attributes.get("data-theme"), "light")
})
