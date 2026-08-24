import { createRequire } from "node:module"

const require = createRequire(import.meta.url)
const playwrightRoot = process.env.PLAYWRIGHT_TEST_NODE_PATH

if (!playwrightRoot) throw new Error("PLAYWRIGHT_TEST_NODE_PATH is not set; enter through nix develop")

const { expect, test } = require(`${playwrightRoot}/playwright/test.js`)

const route = "/project/runtime-architecture"

async function svgTheme(object) {
  return object.evaluate((element) => element.contentDocument?.documentElement.dataset.theme)
}

test("synchronizes diagram objects with explicit and system themes", async ({ page }) => {
  const consoleErrors = []
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text())
  })
  await page.addInitScript(() => window.localStorage.setItem("scalive.docs.theme", "dark"))
  await page.goto(route)

  const objects = page.locator(".docs-diagram object")
  await expect(objects).toHaveCount(3)
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")
  await expect.poll(async () => Promise.all(await objects.all().then((values) => values.map(svgTheme))))
    .toEqual(["dark", "dark", "dark"])

  const selector = page.locator("#docs-theme-selector")
  await selector.selectOption("light")
  await expect.poll(async () => Promise.all(await objects.all().then((values) => values.map(svgTheme))))
    .toEqual(["light", "light", "light"])

  await page.emulateMedia({ colorScheme: "dark" })
  await selector.selectOption("system")
  await expect.poll(async () => Promise.all(await objects.all().then((values) => values.map(svgTheme))))
    .toEqual(["dark", "dark", "dark"])

  await page.emulateMedia({ colorScheme: "light" })
  await expect.poll(async () => Promise.all(await objects.all().then((values) => values.map(svgTheme))))
    .toEqual(["light", "light", "light"])
  expect(consoleErrors).toEqual([])
})

test("compares ownership side by side and stacks it without overflow", async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 900 })
  await page.goto(route)

  const comparison = page.locator("[data-diagram=runtime-ownership]")
  const panels = comparison.locator(".docs-diagram-panel")
  await expect(panels).toHaveCount(2)
  const desktop = await panels.evaluateAll((elements) => elements.map((element) => {
    const box = element.getBoundingClientRect()
    return { left: box.left, right: box.right, top: box.top, width: box.width }
  }))
  expect(desktop[0].top).toBe(desktop[1].top)
  expect(desktop[1].left).toBeGreaterThanOrEqual(desktop[0].right)
  expect(desktop.every((panel) => panel.width > 0)).toBe(true)

  await page.setViewportSize({ width: 1280, height: 900 })
  const stacked = await comparison.evaluate((element) => {
    const comparisonBox = element.getBoundingClientRect()
    const comparisonCenter = comparisonBox.left + comparisonBox.width / 2
    return [...element.querySelectorAll(".docs-diagram-panel")].map((panel) => {
      const panelBox = panel.getBoundingClientRect()
      return {
        centerOffset: Math.abs(panelBox.left + panelBox.width / 2 - comparisonCenter),
        top: panelBox.top,
        width: panelBox.width,
      }
    })
  })
  expect(stacked[1].top).toBeGreaterThan(stacked[0].top)
  expect(stacked.every((panel) => panel.width <= 481)).toBe(true)
  expect(stacked.every((panel) => panel.centerOffset <= 1)).toBe(true)

  await page.setViewportSize({ width: 390, height: 844 })
  const mobile = await panels.evaluateAll((elements) => elements.map((element) => {
    const box = element.getBoundingClientRect()
    const object = element.querySelector("object").getBoundingClientRect()
    return { left: box.left, top: box.top, width: box.width, objectWidth: object.width }
  }))
  expect(mobile[1].top).toBeGreaterThan(mobile[0].top)
  expect(Math.abs(mobile[1].left - mobile[0].left)).toBeLessThanOrEqual(1)
  expect(mobile.every((panel) => panel.objectWidth <= panel.width + 1)).toBe(true)
  const documentOverflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth
  )
  expect(documentOverflow).toBeLessThanOrEqual(1)
})

test("fits the connected turn diagram without horizontal scrolling", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.goto(route)

  const canvas = page.locator("[data-diagram=runtime-connected-turn] .docs-diagram-single-canvas")
  const desktop = await canvas.evaluate((element) => {
    const object = element.querySelector("object")
    return {
      clientWidth: element.clientWidth,
      scrollWidth: element.scrollWidth,
      objectWidth: object.getBoundingClientRect().width,
      objectHeight: object.getBoundingClientRect().height,
      documentOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    }
  })
  expect(desktop.scrollWidth).toBeLessThanOrEqual(desktop.clientWidth + 1)
  expect(desktop.objectWidth).toBeLessThanOrEqual(desktop.clientWidth + 1)
  expect(desktop.objectWidth).toBeGreaterThan(0)
  expect(desktop.objectHeight).toBeGreaterThan(desktop.objectWidth)
  expect(desktop.documentOverflow).toBeLessThanOrEqual(1)
  await expect(canvas).not.toHaveAttribute("tabindex")

  await page.setViewportSize({ width: 390, height: 844 })
  const mobile = await canvas.evaluate((element) => {
    const object = element.querySelector("object")
    const root = object.contentDocument.documentElement
    const sourceBodyFontSize = Number.parseFloat(getComputedStyle(root.querySelector(".body")).fontSize)
    const renderedWidth = object.getBoundingClientRect().width
    return {
      clientWidth: element.clientWidth,
      scrollWidth: element.scrollWidth,
      objectWidth: renderedWidth,
      objectHeight: object.getBoundingClientRect().height,
      effectiveBodyFontSize: sourceBodyFontSize * renderedWidth / root.viewBox.baseVal.width,
      documentOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    }
  })
  expect(mobile.scrollWidth).toBeLessThanOrEqual(mobile.clientWidth + 1)
  expect(mobile.objectWidth).toBeLessThanOrEqual(mobile.clientWidth + 1)
  expect(mobile.objectWidth).toBeLessThan(desktop.objectWidth)
  expect(mobile.objectHeight).toBeGreaterThan(0)
  expect(mobile.effectiveBodyFontSize).toBeGreaterThanOrEqual(10)
  expect(mobile.documentOverflow).toBeLessThanOrEqual(1)
  await expect(page.getByRole("link", { name: "Open Disconnected HTTP SVG" })).toHaveCount(1)
  await expect(page.getByRole("link", { name: "Open Connected WebSocket SVG" })).toHaveCount(1)
  await expect(page.getByRole("link", { name: "Open full-size SVG" })).toHaveCount(1)

  await page.setViewportSize({ width: 1280, height: 900 })
  await page.emulateMedia({ media: "print" })
  const print = await canvas.evaluate((element) => {
    const object = element.querySelector("object").getBoundingClientRect()
    return { width: object.width, height: object.height }
  })
  expect(print.width).toBeLessThanOrEqual(385)
  expect(print.height).toBeLessThanOrEqual(925)
})

test("serves self-describing SVGs with matching arrow shafts and markers", async ({ page }) => {
  await page.goto(route)

  const turnObject = page.locator("[data-diagram=runtime-connected-turn] object")
  await expect.poll(() => turnObject.evaluate((element) => Boolean(element.contentDocument)))
    .toBe(true)
  const colors = await turnObject.evaluate((element) => {
    const document = element.contentDocument
    const shaft = document.querySelector(".arrow-green")
    const marker = document.querySelector("#turn-arrow-green path")
    return {
      shaft: getComputedStyle(shaft).stroke,
      marker: getComputedStyle(marker).fill,
    }
  })
  expect(colors.marker).toBe(colors.shaft)

  const links = await page
    .locator(".docs-diagram-heading a, .docs-diagram-panel-heading a")
    .evaluateAll((elements) => elements.map((element) => element.href))
  for (const link of links) {
    const response = await page.request.get(link)
    const body = await response.text()
    expect(response.ok(), link).toBe(true)
    expect(response.headers()["content-type"]).toContain("image/svg+xml")
    expect(body).toMatch(/<title[^>]*>[^<]+<\/title>/)
    expect(body).toMatch(/<desc[^>]*>[^<]+<\/desc>/)
    expect(body).toContain('svg[data-theme="light"]')
    expect(body).toContain('svg[data-theme="dark"]')
  }
})

test("uses SVG system-theme fallback without JavaScript", async ({ browser }) => {
  const context = await browser.newContext({ javaScriptEnabled: false, colorScheme: "dark" })
  const page = await context.newPage()
  await page.goto("http://127.0.0.1:4005/project/runtime-architecture")

  const object = page.locator(".docs-diagram object").first()
  await expect.poll(() => object.evaluate((element) => Boolean(element.contentDocument)))
    .toBe(true)
  const theme = await object.evaluate((element) => {
    const root = element.contentDocument.documentElement
    return {
      explicit: root.hasAttribute("data-theme"),
      background: getComputedStyle(root).getPropertyValue("--bg").trim(),
    }
  })
  expect(theme.explicit).toBe(false)
  expect(theme.background).toBe("#111114")
  await context.close()
})
