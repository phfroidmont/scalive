import { createRequire } from "node:module"

const require = createRequire(import.meta.url)
const playwrightRoot = process.env.PLAYWRIGHT_TEST_NODE_PATH

if (!playwrightRoot) throw new Error("PLAYWRIGHT_TEST_NODE_PATH is not set; enter through nix develop")

const { expect, test } = require(`${playwrightRoot}/playwright/test.js`)

test("applies and persists explicit themes before the application hook mounts", async ({ page }) => {
  await page.addInitScript(() => window.localStorage.setItem("scalive.docs.theme", "dark"))
  await page.goto("/")

  const root = page.locator("html")
  const selector = page.locator("#docs-theme-selector")
  const control = page.locator(".docs-theme-control")
  await expect(root).toHaveAttribute("data-theme", "dark")
  await expect(selector).toHaveValue("dark")
  await expect(selector).toHaveAttribute("aria-label", "Color theme: Dark")
  await expect(control.locator(".docs-theme-icon")).toHaveAttribute("aria-hidden", "true")
  await expect(control).toHaveCSS("width", "44px")
  await expect(control).toHaveCSS("height", "44px")

  await selector.selectOption("light")
  await expect(root).toHaveAttribute("data-theme", "light")
  await expect(selector).toHaveAttribute("title", "Color theme: Light")
  expect(await page.evaluate(() => window.localStorage.getItem("scalive.docs.theme"))).toBe("light")

  await selector.selectOption("system")
  await expect(root).not.toHaveAttribute("data-theme")
  expect(await page.evaluate(() => window.localStorage.getItem("scalive.docs.theme"))).toBeNull()
})

test("falls back to the system theme when storage is unavailable", async ({ page }) => {
  await page.addInitScript(() => {
    Storage.prototype.getItem = () => {
      throw new Error("storage unavailable")
    }
  })
  await page.goto("/")
  await expect(page.locator("html")).not.toHaveAttribute("data-theme")
  await expect(page.locator("#docs-theme-selector")).toHaveValue("system")
})

test("keeps text and control boundaries above their contrast thresholds", async ({ page }) => {
  for (const theme of ["light", "dark"]) {
    await page.addInitScript((value) => window.localStorage.setItem("scalive.docs.theme", value), theme)
    await page.goto("/search?q=scalive.LiveView")
    const ratios = await page.evaluate(() => {
      const luminance = (color) => {
        const channels = color.match(/[\d.]+/g).slice(0, 3).map((value) => Number(value) / 255)
        return channels.map((value) =>
          value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4
        ).reduce((sum, value, index) => sum + value * [0.2126, 0.7152, 0.0722][index], 0)
      }
      const contrast = (first, second) => {
        const values = [luminance(first), luminance(second)].sort((left, right) => right - left)
        return (values[0] + 0.05) / (values[1] + 0.05)
      }
      const link = document.querySelector(".docs-search-result > a")
      const button = document.querySelector(".docs-search-page-form button")
      const input = document.querySelector("#docs-global-search-input")
      const linkStyle = getComputedStyle(link)
      const buttonStyle = getComputedStyle(button)
      const inputStyle = getComputedStyle(input)

      return {
        link: contrast(linkStyle.color, getComputedStyle(document.body).backgroundColor),
        button: contrast(buttonStyle.color, buttonStyle.backgroundColor),
        input: contrast(inputStyle.borderTopColor, inputStyle.backgroundColor),
      }
    })

    expect(ratios.link, `${theme} link contrast`).toBeGreaterThanOrEqual(4.5)
    expect(ratios.button, `${theme} action contrast`).toBeGreaterThanOrEqual(4.5)
    expect(ratios.input, `${theme} input boundary contrast`).toBeGreaterThanOrEqual(3)
  }
})

test("uses one native mobile disclosure without duplicating header controls", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto("/")

  const disclosure = page.locator("#docs-navigation-disclosure")
  await expect(disclosure).not.toHaveAttribute("open")
  await expect(page.locator("#docs-global-search")).toHaveCount(1)
  await expect(page.locator("#docs-theme-selector")).toHaveCount(1)

  await disclosure.locator("summary").click()
  await expect(disclosure).toHaveAttribute("open", "")
  const learn = disclosure.getByRole("link", { name: "Learn", exact: true })
  await expect(learn).toHaveAttribute("href", "/learn")
  await learn.click()
  await expect(page).toHaveURL(/\/learn$/)
  await expect(disclosure).not.toHaveAttribute("open")
})

test("focuses documentation search with the advertised keyboard shortcut", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto("/")

  const disclosure = page.locator("#docs-navigation-disclosure")
  const search = page.locator("#docs-global-search-input")
  await expect(page.locator(".docs-global-search-control kbd")).toHaveText("Ctrl K")

  await page.keyboard.press("Control+k")
  await expect(disclosure).toHaveAttribute("open", "")
  await expect(search).toBeFocused()
})

test("connects the focused homepage action to a typed server transition", async ({ page }) => {
  await page.goto("/")

  const example = page.locator("#example-counter")
  await expect(example.getByRole("button", { name: "Decrease" })).toBeHidden()
  await expect(example.getByRole("button", { name: "Reset" })).toBeHidden()
  await example.getByRole("button", { name: "Increase" }).click()
  await expect(example.locator(".docs-counter-flow")).toContainText(
    "browser event→Msg.Increment→server state: 1→HTML diff",
  )
})

test("copies exact code and expands the same long source block", async ({ context, page }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"])
  await page.goto("/examples")

  const block = page.locator(".docs-code-block[data-code-expandable]").first()
  const code = block.locator(".docs-code > code")
  const copy = block.locator("[data-code-copy]")
  const expand = block.locator("[data-code-expand]")
  const expected = await code.textContent()

  await expect(copy).toBeVisible()
  await expect(expand).toHaveAttribute("aria-expanded", "false")
  await expect(block).toHaveClass(/docs-code-collapsed/)

  await copy.click()
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(expected)
  await expect(block.locator("[data-code-status]")).toHaveText("Code copied")

  await expand.click()
  await expect(expand).toHaveAttribute("aria-expanded", "true")
  await expect(block).not.toHaveClass(/docs-code-collapsed/)
  await expect(block.locator(".docs-code > code")).toHaveCount(1)

  await page.goto("/api/scalive/live-view")
  await expect(page.locator(".docs-api-signature [data-code-copy]").first()).toBeVisible()
})

test("keeps complete code visible without JavaScript", async ({ browser }) => {
  const context = await browser.newContext({ javaScriptEnabled: false })
  const page = await context.newPage()
  await page.goto("/examples")

  const block = page.locator(".docs-code-block[data-code-expandable]").first()
  await expect(block.locator("[data-code-copy]")).toBeHidden()
  await expect(block.locator("[data-code-expand]")).toBeHidden()
  await expect(block.locator(".docs-code > code")).toContainText("class CounterExample")
  await expect(block).not.toHaveClass(/docs-code-collapsed/)

  await context.close()
})

test("keeps focus visible and suppresses motion for local controls", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" })
  await page.goto("/examples")
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")

  const selectors = [
    ".docs-brand",
    ".docs-primary-nav a",
    "#docs-global-search-input",
    "#docs-theme-selector",
    "[data-code-copy]",
    "[data-example-controls] button",
    ".docs-xray button",
    ".docs-page-links a",
  ]
  const remaining = new Set(selectors)
  for (let index = 0; index < 100 && remaining.size > 0; index += 1) {
    await page.keyboard.press("Tab")
    const focused = await page.evaluate((candidates) => {
      const element = document.activeElement
      const selector = candidates.find((candidate) => element.matches(candidate))
      if (!selector) return undefined
      const style = getComputedStyle(element)
      return {
        selector,
        focusVisible: element.matches(":focus-visible"),
        boxShadow: style.boxShadow,
        style: style.outlineStyle,
      }
    }, [...remaining])
    if (focused) {
      expect(focused.focusVisible, focused.selector).toBe(true)
      expect(focused.boxShadow, focused.selector).not.toBe("none")
      expect(focused.style, focused.selector).toBe("solid")
      remaining.delete(focused.selector)
    }
  }
  expect([...remaining]).toEqual([])

  const transitionDuration = await page.locator(".docs-code-enhanced .docs-code").first()
    .evaluate((element) => getComputedStyle(element).transitionDuration)
  const transitionMilliseconds = transitionDuration.endsWith("ms")
    ? Number.parseFloat(transitionDuration)
    : Number.parseFloat(transitionDuration) * 1000
  expect(transitionMilliseconds).toBeLessThanOrEqual(0.01)

  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto("/")
  await page.keyboard.press("Tab")
  await page.keyboard.press("Tab")
  await page.keyboard.press("Tab")
  const summary = page.locator("#docs-navigation-disclosure summary")
  await expect(summary).toBeFocused()
  const summaryOutline = await summary.evaluate((element) => {
    const style = getComputedStyle(element)
    return { boxShadow: style.boxShadow, style: style.outlineStyle }
  })
  expect(summaryOutline.boxShadow).not.toBe("none")
  expect(summaryOutline.style).toBe("solid")
})

test("contains representative pages locally without third-party requests or document overflow", async ({ page }) => {
  const foreignRequests = []
  const consoleErrors = []
  const pageErrors = []
  page.on("request", (request) => {
    const url = new URL(request.url())
    if (url.origin !== "http://127.0.0.1:4005") foreignRequests.push(url.href)
  })
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text())
  })
  page.on("pageerror", (error) => pageErrors.push(error.message))

  for (const theme of ["light", "dark"]) {
    await page.goto("/")
    await page.evaluate((value) => window.localStorage.setItem("scalive.docs.theme", value), theme)
    for (const viewport of [
      { width: 390, height: 844 },
      { width: 1440, height: 1000 },
    ]) {
      await page.setViewportSize(viewport)
      for (const route of [
        "/",
        "/learn",
        "/examples",
        "/api",
        "/api/scalive/live-view",
        "/search?q=scalive.LiveView",
        "/project",
      ]) {
        await page.goto(route)
        await expect(page.locator("html")).toHaveAttribute("data-theme", theme)
        await expect(page.locator("#docs-main")).toBeVisible()
        const overflow = await page.evaluate(() =>
          document.documentElement.scrollWidth - document.documentElement.clientWidth
        )
        expect(overflow, `${route} at ${viewport.width}px in ${theme}`).toBeLessThanOrEqual(1)
      }
    }
  }

  expect(foreignRequests).toEqual([])
  expect(consoleErrors).toEqual([])
  expect(pageErrors).toEqual([])
})
