import { expect, test } from "./playwright.js"

test("keeps public page metadata indexable through live navigation", async ({ page }) => {
  const connectionState = () => page.evaluate(() => window.liveSocket?.socket?.connectionState?.())
  const metadata = page.locator("#docs-page-metadata")
  const robots = page.locator('meta[name="robots"]')

  await page.goto("/")
  await expect.poll(connectionState).toBe("open")
  await expect(metadata).toHaveAttribute("data-page-indexable", "true")
  await expect(robots).toHaveCount(0)

  await page.goto("/search")
  await expect.poll(connectionState).toBe("open")
  await expect(metadata).toHaveAttribute("data-page-indexable", "false")
  await expect(robots).toHaveAttribute("content", "noindex,follow")

  await page.getByRole("link", { name: "Learn", exact: true }).click()
  await expect(page).toHaveURL(/\/learn$/)
  await expect(metadata).toHaveAttribute("data-page-indexable", "true")
  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute(
    "href",
    "http://127.0.0.1:4005/learn",
  )
  await expect(page.locator('meta[name="description"]')).toHaveAttribute(
    "content",
    "Understand Scalive's server-owned programming model, then build and reason about a complete LiveView application.",
  )
  await expect(robots).toHaveCount(0)
})

test("applies and persists explicit themes before the application hook mounts", async ({ page }) => {
  await page.addInitScript(() => window.localStorage.setItem("scalive.docs.theme", "dark"))
  await page.goto("/")

  const root = page.locator("html")
  const toggle = page.locator("#docs-theme-selector")
  await expect(root).toHaveAttribute("data-theme", "dark")
  await expect(toggle).toHaveAttribute("data-theme-effective", "dark")
  await expect(toggle).toHaveAttribute("aria-label", "Switch to light theme")
  await expect(toggle.locator(".docs-theme-icon-dark")).toHaveAttribute("aria-hidden", "true")

  await toggle.click()
  await expect(root).toHaveAttribute("data-theme", "light")
  await expect(toggle).toHaveAttribute("title", "Switch to dark theme")
  expect(await page.evaluate(() => window.localStorage.getItem("scalive.docs.theme"))).toBe("light")

  await toggle.press("Enter")
  await expect(root).toHaveAttribute("data-theme", "dark")
  expect(await page.evaluate(() => window.localStorage.getItem("scalive.docs.theme"))).toBe("dark")
})

test("falls back to the system theme when storage is unavailable", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "dark" })
  await page.addInitScript(() => {
    Storage.prototype.getItem = () => {
      throw new Error("storage unavailable")
    }
  })
  await page.goto("/")
  await expect(page.locator("html")).not.toHaveAttribute("data-theme")
  const toggle = page.locator("#docs-theme-selector")
  await expect(toggle).toHaveAttribute("aria-label", "Toggle color theme")
  await expect(toggle.locator(".docs-theme-icon-dark")).toBeVisible()
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

test("hides the API tree and keeps the outline before mobile content", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto("/api/scalive/live-view")

  const apiNavigation = page.locator(".docs-api-navigation")
  const outline = page.locator(".docs-outline-disclosure")
  const main = page.locator("#docs-main")
  await expect(apiNavigation).toBeHidden()
  await expect(outline).not.toHaveAttribute("open")
  expect(await outline.locator(":scope > summary").evaluate((element) => element.getBoundingClientRect().top))
    .toBeLessThan(await main.evaluate((element) => element.getBoundingClientRect().top))

  await outline.locator(":scope > summary").click()
  await expect(outline.getByRole("link", { name: "Methods", exact: true })).toBeVisible()
})

test("filters API package members and keeps the outline in sync", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto("/api/scalive")

  const navigation = page.locator(".docs-api-navigation")
  const outline = page.locator(".docs-outline")
  const pageContent = page.locator(".docs-api-page")
  await expect(navigation.getByRole("link", { name: "rawHtml", exact: true })).toHaveCount(0)

  const filter = pageContent.locator("[data-api-member-filter]")
  await expect(filter).toBeVisible()
  await filter.fill("rawHtml")
  await expect(pageContent.locator('[data-api-symbol="def:scalive.rawHtml"]')).toBeVisible()
  await expect(pageContent.locator("[data-api-member]:visible")).toHaveCount(1)
  await expect(pageContent.locator("[data-api-member-status]")).toHaveText("1 member")
  await expect(pageContent.locator("#html-elements")).toBeHidden()
  await expect(outline.getByRole("link", { name: "HTML elements", exact: true })).toBeHidden()

  await filter.fill("html elements")
  await expect(pageContent.locator("#html-elements")).toBeVisible()
  await expect(pageContent.locator("#html-attributes")).toBeHidden()
  await expect(outline.getByRole("link", { name: "HTML elements", exact: true })).toBeVisible()
})

test("tracks the current section in the page outline", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 700 })
  await page.goto("/learn/models-and-messages")

  const outline = page.locator(".docs-outline")
  const first = outline.getByRole("link", { name: "One Model, One Message Type", exact: true })
  const last = outline.getByRole("link", { name: "Keep Messages Meaningful", exact: true })
  await expect(first).toHaveAttribute("aria-current", "location")
  await expect(outline.locator('[aria-current="location"]')).toHaveCount(1)

  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
  await expect(last).toHaveAttribute("aria-current", "location")
  await expect(outline.locator('[aria-current="location"]')).toHaveCount(1)

  await first.click()
  await expect(page).toHaveURL(/#one-model-one-message-type$/)
  await expect(first).toHaveAttribute("aria-current", "location")
})

test("keeps the current API item visible and its package disclosure interactive", async ({ page }) => {
  await page.setViewportSize({ width: 960, height: 1000 })
  await page.goto("/api/scalive/live-view")

  const navigation = page.locator(".docs-section-nav")
  const current = navigation.locator('[aria-current="page"]')
  await expect(current.locator(".docs-api-nav-label")).toHaveText("LiveView")
  await expect(navigation.locator('a[href="/api/scalive/live-view/companion"]')).toBeVisible()
  await expect.poll(async () => current.evaluate((element) => {
    const bounds = element.getBoundingClientRect()
    const container = element.closest(".docs-section-nav").getBoundingClientRect()
    return bounds.top >= container.top && bounds.bottom <= container.bottom
  })).toBe(true)

  const packageEntry = navigation.locator('[data-api-nav-item="scalive"] > details > summary')
  const packageDetails = packageEntry.locator("..")
  const initialUrl = page.url()
  await packageEntry.press("Space")
  await expect(packageDetails).not.toHaveAttribute("open")
  expect(page.url()).toBe(initialUrl)
  await packageEntry.press("Space")
  await expect(packageDetails).toHaveAttribute("open", "")
})

test("connects the focused homepage action to a typed server transition", async ({ page }) => {
  await page.goto("/")

  const example = page.locator("#example-counter")
  await expect(example.getByRole("button", { name: "Decrease" })).toBeHidden()
  await expect(example.getByRole("button", { name: "Reset" })).toBeHidden()
  await example.getByRole("button", { name: "Increase" }).click()
  await expect(example.locator("[role=status] strong")).toHaveText("1")
})

test("copies exact code and expands the same long source block", async ({ context, page }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"])
  await page.goto("/examples/counter")

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
  await expect(page.locator(".docs-api-page [data-code-copy]")).toHaveCount(0)
})

test("keeps a representative desktop and mobile shell accessible and overflow-free", async ({ page }) => {
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

  for (const sample of [
    { viewport: { width: 1440, height: 1000 }, route: "/api/scalive/live-view" },
    { viewport: { width: 390, height: 844 }, route: "/learn/models-and-messages" },
  ]) {
    await page.setViewportSize(sample.viewport)
    await page.goto(sample.route)
    await expect(page.getByRole("main")).toBeVisible()
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible()
    const overflow = await page.evaluate(() =>
      document.documentElement.scrollWidth - document.documentElement.clientWidth
    )
    expect(overflow, `${sample.route} at ${sample.viewport.width}px`).toBeLessThanOrEqual(1)

    if (sample.viewport.width === 390) {
      await page.keyboard.press("Tab")
      await page.keyboard.press("Tab")
      await page.keyboard.press("Tab")
      const summary = page.locator("#docs-navigation-disclosure summary")
      await expect(summary).toBeFocused()
      expect(await summary.evaluate((element) => element.matches(":focus-visible"))).toBe(true)
    } else {
      await page.keyboard.press("Control+k")
      const search = page.locator("#docs-global-search-input")
      await expect(search).toBeFocused()
      expect(await search.evaluate((element) => element.matches(":focus-visible"))).toBe(true)
    }
  }

  expect(foreignRequests).toEqual([])
  expect(consoleErrors).toEqual([])
  expect(pageErrors).toEqual([])
})
