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

test("organizes API package members without duplicating them in side navigation", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto("/api/scalive")

  const navigation = page.locator(".docs-api-navigation")
  const outline = page.locator(".docs-outline")
  const pageContent = page.locator(".docs-api-page")
  await expect(navigation.getByText("Packages and types", { exact: true })).toBeVisible()
  await expect(navigation.locator("[data-api-nav-filter]"))
    .toHaveAttribute("placeholder", "Filter packages and types")
  await expect(navigation.getByRole("link", { name: "rawHtml", exact: true })).toHaveCount(0)
  expect(await navigation.locator("[data-api-kind]").evaluateAll((badges) =>
    [...new Set(badges.map((badge) => badge.dataset.apiKind))].sort()
  )).toEqual(["class", "enum", "object", "package", "trait"])
  const titleRow = pageContent.locator(".docs-api-title-row")
  await expect(titleRow.locator(".docs-api-title-kind")).toHaveText("p")
  await expect(titleRow.locator(".docs-api-title-kind"))
    .toHaveAttribute("aria-label", "Package")
  await expect(titleRow.locator(".docs-api-title-kind + h1")).toHaveText("scalive")
  await expect(pageContent.locator(".docs-api-qualified-name")).toHaveCount(0)
  const titleBadgeLayout = await pageContent.evaluate((element) => {
    const titleBadge = element.querySelector(".docs-api-title-kind-package")
    const heading = element.querySelector(".docs-api-title-row h1")
    const titleBounds = titleBadge.getBoundingClientRect()
    const headingBounds = heading.getBoundingClientRect()
    return {
      centerDifference: Math.abs(
        titleBounds.top + titleBounds.height / 2 - (headingBounds.top + headingBounds.height / 2)
      ),
    }
  })
  expect(titleBadgeLayout.centerDifference).toBeLessThanOrEqual(1)

  await expect(pageContent.locator("#html-elements")).toBeVisible()
  await expect(pageContent.locator("#html-attributes")).toBeVisible()
  await expect(outline.getByRole("link", { name: "HTML elements", exact: true })).toBeVisible()
  await expect(outline.getByRole("link", { name: "HTML attributes", exact: true })).toBeVisible()
  await expect(outline.getByRole("link", { name: "div", exact: true })).toHaveCount(0)

  const simpleMember = pageContent.locator('[data-api-symbol="def:scalive.rawHtml"]')
  await expect(simpleMember.locator("h3.docs-visually-hidden")).toHaveText("rawHtml")
  await expect(simpleMember.locator(".docs-api-kind")).toHaveCount(0)
  await expect(simpleMember.locator(".docs-code-block, [data-code-copy]")).toHaveCount(0)
  const syntaxColors = await simpleMember.locator(".docs-api-member-signature").first().evaluate((element) => ({
    keyword: getComputedStyle(element.querySelector(".keyword")).color,
    text: getComputedStyle(element).color,
    type: getComputedStyle(element.querySelector(".type-name")).color,
  }))
  expect(syntaxColors.keyword).not.toBe(syntaxColors.text)
  expect(syntaxColors.type).not.toBe(syntaxColors.text)

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

test("keeps every categorized API member visible without JavaScript", async ({ browser }) => {
  const context = await browser.newContext({ javaScriptEnabled: false })
  const page = await context.newPage()
  await page.goto("http://127.0.0.1:4005/api/scalive")

  await expect(page.locator("[data-api-member-tools]")).toBeHidden()
  const rawHtml = page.locator('[data-api-symbol="def:scalive.rawHtml"]')
  await expect(rawHtml).toBeVisible()
  await expect(rawHtml.locator("pre.docs-api-member-signature code").first()).toBeVisible()
  await expect(rawHtml.getByRole("link", { name: "View source" }).first()).toBeVisible()
  await expect(rawHtml.locator(".docs-code-block, [data-code-copy]")).toHaveCount(0)
  await expect(page.locator("#html-elements [data-api-member]").first()).toBeVisible()
  await context.close()
})

test("wraps API declarations without document overflow or copy controls", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto("/api/scalive")

  const signatures = page.locator(".docs-api-member-signature")
  const member = signatures.filter({ hasText: "liveComponent" }).first()
  const declaration = page.locator(".docs-api-page > .docs-api-symbol .docs-code-block")
  await expect(member).toBeVisible()
  await expect(declaration).toBeVisible()
  await expect(declaration.locator(".docs-code-toolbar, [data-code-copy]")).toHaveCount(0)
  for (const element of [member, declaration]) {
    const layout = await element.evaluate((container) => {
      const surface = container.matches("pre") ? container : container.querySelector("pre") || container
      const code = container.querySelector("code")
      return {
        overflow: surface.scrollWidth - surface.clientWidth,
        documentOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
        wraps: code.getBoundingClientRect().height > Number.parseFloat(getComputedStyle(code).lineHeight) * 1.5,
      }
    })
    expect(layout.wraps).toBe(true)
    expect(layout.overflow).toBeLessThanOrEqual(1)
    expect(layout.documentOverflow).toBeLessThanOrEqual(1)
  }
})

test("shows a flat Learn path on desktop and hides it on smaller screens", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.goto("/learn/models-and-messages")

  const navigation = page.locator(".docs-section-index")
  await expect(navigation).toBeVisible()
  await expect(navigation.locator("details")).toHaveCount(0)
  await expect(navigation.locator('[aria-current="page"]')).toHaveText("04Models, messages, and effects")

  await page.setViewportSize({ width: 768, height: 900 })
  await expect(navigation).toBeHidden()
})

test("tracks the current section in the page outline", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 700 })
  await page.goto("/learn/models-and-messages")

  const outline = page.locator(".docs-outline")
  const first = outline.getByRole("link", { name: "One Model, One Message Type", exact: true })
  const last = outline.getByRole("link", { name: "Keep Messages Meaningful", exact: true })
  await expect(first).toHaveAttribute("aria-current", "location")
  await expect(outline.locator('[aria-current="location"]')).toHaveCount(1)

  const guideColors = await outline.locator("a").evaluateAll((links) =>
    links.slice(0, 2).map((link) => getComputedStyle(link).borderLeftColor)
  )
  expect(guideColors[0]).not.toBe(guideColors[1])
  const guideGap = await outline.locator("a").evaluateAll((links) =>
    links[1].getBoundingClientRect().top - links[0].getBoundingClientRect().bottom
  )
  expect(Math.abs(guideGap)).toBeLessThanOrEqual(0.5)

  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
  await expect(last).toHaveAttribute("aria-current", "location")
  await expect(outline.locator('[aria-current="location"]')).toHaveCount(1)

  await first.click()
  await expect(page).toHaveURL(/#one-model-one-message-type$/)
  await expect(first).toHaveAttribute("aria-current", "location")
})

test("shows sibling companion entries without tree guide lines", async ({ page }) => {
  await page.setViewportSize({ width: 960, height: 1000 })
  await page.goto("/api/scalive/live-view")

  const navigation = page.locator(".docs-section-nav")
  const current = navigation.locator('[aria-current="page"]')
  const packageChildren = navigation.locator('[data-api-nav-item="scalive"] > details > ul > li')
  const liveViewLabels = packageChildren.locator(
    ":scope > details > summary .docs-api-nav-label, :scope > .docs-nav-leaf .docs-api-nav-label"
  ).filter({ hasText: /^LiveView$/ })
  await expect.poll(async () => navigation.evaluate((element) => element.getBoundingClientRect().width))
    .toBeGreaterThanOrEqual(288)
  await expect(navigation.locator("nav > ul > li")).toHaveAttribute("data-api-nav-item", "scalive")
  await expect(navigation.locator('[data-api-nav-item="api"]')).toHaveCount(0)
  await expect(current.locator(".docs-api-nav-label")).toHaveText("LiveView")
  await expect(liveViewLabels).toHaveCount(2)
  await expect(navigation.locator('a[href="/api/scalive/live-view/companion"]')).toBeVisible()
  await expect(page.getByText("Browse API", { exact: true })).toHaveCount(0)
  await expect(navigation.locator(".docs-tree-marker").first()).toHaveText("")
  expect(await navigation.evaluate((element) => getComputedStyle(element).borderLeftWidth)).toBe("0px")
  expect(await navigation.locator("details > ul").first()
    .evaluate((element) => getComputedStyle(element).borderLeftWidth)).toBe("0px")
  const entryLefts = await navigation.locator(
    '[data-api-nav-item="scalive"] > details > summary, [data-api-nav-item="afterrendercontext"] > .docs-api-nav-entry'
  ).evaluateAll((entries) => entries.map((entry) => entry.getBoundingClientRect().left))
  expect(Math.max(...entryLefts) - Math.min(...entryLefts)).toBeLessThanOrEqual(0.5)
  const liveViewLefts = await liveViewLabels.evaluateAll((labels) =>
    labels.map((label) => label.getBoundingClientRect().left)
  )
  expect(Math.max(...liveViewLefts) - Math.min(...liveViewLefts)).toBeLessThanOrEqual(0.5)
  await expect(current.locator("[data-api-kind]")).toBeVisible()
  await expect(current.locator(".docs-api-nav-label")).toHaveText("LiveView")
  await expect.poll(async () => current.evaluate((element) => {
    const bounds = element.getBoundingClientRect()
    const container = element.closest(".docs-section-nav").getBoundingClientRect()
    return bounds.top >= container.top && bounds.bottom <= container.bottom
  })).toBe(true)

  const packageEntry = navigation.locator('[data-api-nav-item="scalive"] > details > summary')
  const packageDetails = packageEntry.locator("..")
  const initialUrl = page.url()
  await packageEntry.click({ position: { x: 8, y: 14 } })
  await expect(packageDetails).not.toHaveAttribute("open")
  expect(page.url()).toBe(initialUrl)
  await packageEntry.click({ position: { x: 8, y: 14 } })
  await expect(packageDetails).toHaveAttribute("open", "")

  const leafEntry = navigation.locator('[data-api-nav-item="afterrendercontext"] > .docs-api-nav-entry')
  const restingBackground = await leafEntry.evaluate((entry) => getComputedStyle(entry).backgroundColor)
  await leafEntry.hover()
  await expect.poll(() => leafEntry.evaluate((entry) => getComputedStyle(entry).backgroundColor))
    .not.toBe(restingBackground)
  const siblingNames = await navigation.locator('[data-api-nav-item="scalive"]')
    .evaluate((item) => [...item.querySelectorAll(":scope > details > ul > li")].map((child) => {
      const row = child.querySelector(":scope > details > summary .docs-nav-row, :scope > .docs-nav-leaf .docs-nav-row")
      return row.querySelector(".docs-api-nav-label").textContent
    }))
  const sortedNames = [...siblingNames].sort((left, right) =>
    left.toLowerCase().localeCompare(right.toLowerCase())
  )
  expect(siblingNames).toEqual(sortedNames)

  await page.goto("/search?q=scalive.LiveView")
  await expect(page.locator('.docs-search-results a[href="/api/scalive/live-view"]')).toHaveCount(1)
  await expect(page.locator('.docs-search-results a[href="/api/scalive/live-view/companion"]'))
    .toHaveText("scalive.LiveView companion object")
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

test("keeps complete code visible without JavaScript", async ({ browser }) => {
  const context = await browser.newContext({ javaScriptEnabled: false })
  const page = await context.newPage()
  await page.goto("/examples/counter")

  const block = page.locator(".docs-code-block[data-code-expandable]").first()
  await expect(block.locator("[data-code-copy]")).toBeHidden()
  await expect(block.locator("[data-code-expand]")).toBeHidden()
  await expect(block.locator(".docs-code > code")).toContainText("class CounterExample")
  await expect(block).not.toHaveClass(/docs-code-collapsed/)

  await context.close()
})

test("keeps focus visible and suppresses motion for local controls", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" })
  await page.goto("/examples/counter")
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")

  const selectors = [
    ".docs-brand",
    ".docs-primary-nav a",
    "#docs-global-search-input",
    "#docs-theme-selector",
    "[data-code-copy]",
    "[data-example-controls] button",
    ".docs-live-trace button",
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
