import { expect, test } from "./playwright.js"

const route = "/project/runtime-architecture"

async function svgTheme(object) {
  return object.evaluate((element) => element.contentDocument?.documentElement.dataset.theme)
}

test("synchronizes diagram objects with explicit and system themes", async ({ page }) => {
  const consoleErrors = []
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text())
  })
  await page.emulateMedia({ colorScheme: "dark" })
  await page.goto(route)

  const objects = page.locator(".docs-diagram object")
  await expect(objects).toHaveCount(3)
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")
  await expect.poll(async () => Promise.all(await objects.all().then((values) => values.map(svgTheme))))
    .toEqual(["dark", "dark", "dark"])

  await page.emulateMedia({ colorScheme: "light" })
  await expect.poll(async () => Promise.all(await objects.all().then((values) => values.map(svgTheme))))
    .toEqual(["light", "light", "light"])

  const toggle = page.locator("#docs-theme-selector")
  await toggle.click()
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark")
  await expect.poll(async () => Promise.all(await objects.all().then((values) => values.map(svgTheme))))
    .toEqual(["dark", "dark", "dark"])

  await page.emulateMedia({ colorScheme: "light" })
  await expect.poll(async () => Promise.all(await objects.all().then((values) => values.map(svgTheme))))
    .toEqual(["dark", "dark", "dark"])
  expect(consoleErrors).toEqual([])
})

test("keeps a representative diagram responsive without horizontal scrolling", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.goto(route)

  const canvas = page.locator("[data-diagram=runtime-connected-turn] .docs-diagram-single-canvas")
  for (const viewport of [
    { width: 1280, height: 900 },
    { width: 390, height: 844 },
  ]) {
    await page.setViewportSize(viewport)
    await expect(canvas.locator("object")).toBeVisible()
    const fits = await canvas.evaluate((element) =>
      element.scrollWidth - element.clientWidth <= 1
      && document.documentElement.scrollWidth - document.documentElement.clientWidth <= 1
    )
    expect(fits, `${viewport.width}px viewport`).toBe(true)
  }
})

test("uses SVG system-theme fallback without JavaScript", async ({ browser }) => {
  const context = await browser.newContext({ javaScriptEnabled: false, colorScheme: "dark" })
  try {
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
  } finally {
    await context.close()
  }
})
