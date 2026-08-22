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
  await expect(objects).toHaveCount(2)
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")
  await expect.poll(async () => Promise.all(await objects.all().then((values) => values.map(svgTheme))))
    .toEqual(["dark", "dark"])

  const selector = page.locator("#docs-theme-selector")
  await selector.selectOption("light")
  await expect.poll(async () => Promise.all(await objects.all().then((values) => values.map(svgTheme))))
    .toEqual(["light", "light"])

  await page.emulateMedia({ colorScheme: "dark" })
  await selector.selectOption("system")
  await expect.poll(async () => Promise.all(await objects.all().then((values) => values.map(svgTheme))))
    .toEqual(["dark", "dark"])

  await page.emulateMedia({ colorScheme: "light" })
  await expect.poll(async () => Promise.all(await objects.all().then((values) => values.map(svgTheme))))
    .toEqual(["light", "light"])
  expect(consoleErrors).toEqual([])
})

test("contains wide diagrams without document overflow", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(route)

  const wideViewport = page.locator(".docs-diagram-wide .docs-diagram-viewport").first()
  const wideLayout = await wideViewport.evaluate((element) => {
    const object = element.querySelector("object")
    return {
      clientWidth: element.clientWidth,
      scrollWidth: element.scrollWidth,
      objectWidth: object.getBoundingClientRect().width,
      objectHeight: object.getBoundingClientRect().height,
      documentOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    }
  })
  expect(wideLayout.scrollWidth).toBeGreaterThan(wideLayout.clientWidth)
  expect(wideLayout.objectWidth).toBeGreaterThanOrEqual(960)
  expect(wideLayout.objectHeight).toBeGreaterThan(0)
  expect(wideLayout.documentOverflow).toBeLessThanOrEqual(1)
  await wideViewport.focus()
  await expect(wideViewport).toBeFocused()
  await expect(page.getByRole("link", { name: "Open full-size SVG" })).toHaveCount(2)
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

  const links = await page.locator(".docs-diagram-heading a").evaluateAll((elements) =>
    elements.map((element) => element.href)
  )
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
