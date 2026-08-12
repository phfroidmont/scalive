import { createRequire } from "node:module"

const require = createRequire(import.meta.url)
const playwrightRoot = process.env.PLAYWRIGHT_TEST_NODE_PATH

if (!playwrightRoot) throw new Error("PLAYWRIGHT_TEST_NODE_PATH is not set; enter through nix develop")

const { expect, test } = require(`${playwrightRoot}/playwright/test.js`)

const references = (page) => page.locator(".docs-inline-api-reference")

test.beforeEach(async ({ page }) => {
  await page.goto("/learn/models-and-messages")
})

test("opens inline API references on hover and keeps only one open", async ({ page }) => {
  const first = references(page).first()
  const firstTrigger = first.locator("a[data-api-reference-trigger]")
  const firstPreview = first.locator("[data-api-reference-preview]")

  await firstTrigger.dispatchEvent("pointerover")
  await expect(firstPreview).toBeHidden({ timeout: 75 })
  await expect(firstPreview).toBeVisible()
  await expect(firstTrigger).toHaveText("LiveView[Msg, Model]")
  await expect(firstPreview.locator(".docs-api-reference-kind")).toHaveCount(0)
  await expect(firstPreview.locator("code.docs-api-reference-signature, pre .docs-api-reference-signature"))
    .toHaveCount(0)
  await expect(firstPreview.locator(".docs-api-reference-signature .keyword")).toHaveText("trait")
  await expect(firstPreview.locator(".docs-api-reference-signature .type-name").first()).toHaveText("LiveView")
  const signatureSurface = await firstPreview.locator(".docs-api-reference-signature").evaluate((element) => {
    const style = getComputedStyle(element)
    return {
      background: style.backgroundColor,
      border: style.borderTopWidth,
      padding: style.paddingTop,
    }
  })
  expect(signatureSurface).toEqual({
    background: "rgba(0, 0, 0, 0)",
    border: "0px",
    padding: "0px",
  })
  await expect(firstPreview).toHaveAttribute("id", /docs-api-reference-preview-\d+/)
  await expect(firstTrigger).toHaveAttribute("aria-describedby", await firstPreview.getAttribute("id"))

  await first.evaluate((element) => {
    const clone = element.cloneNode(true)
    clone.querySelector("[data-api-reference-trigger]").removeAttribute("aria-describedby")
    clone.querySelector("[data-api-reference-preview]").removeAttribute("id")
    element.after(clone)
  })
  const second = references(page).nth(1)
  const secondPreview = second.locator("[data-api-reference-preview]")
  await second.locator("a[data-api-reference-trigger]").hover()
  await expect(secondPreview).toBeVisible()
  await expect(firstPreview).toBeHidden()
})

test("keeps the preview open within the wrapper and closes it outside", async ({ page }) => {
  const reference = references(page).first()
  const preview = reference.locator("[data-api-reference-preview]")

  await reference.locator("a[data-api-reference-trigger]").hover()
  await expect(preview).toBeVisible()
  await preview.hover()
  await expect(preview).toBeVisible()
  await page.locator("h1").hover()
  await expect(preview).toBeHidden()
})

test("supports keyboard focus and Escape without moving focus", async ({ page }) => {
  const reference = references(page).first()
  const trigger = reference.locator("a[data-api-reference-trigger]")
  const preview = reference.locator("[data-api-reference-preview]")

  await trigger.focus()
  await expect(preview).toBeVisible()
  await page.keyboard.press("Escape")
  await expect(preview).toBeHidden()
  await expect(trigger).toBeFocused()
  await expect(trigger).not.toHaveAttribute("aria-describedby")
})

test("stays open while either pointer or focus remains in the wrapper", async ({ page }) => {
  const reference = references(page).first()
  const trigger = reference.locator("a[data-api-reference-trigger]")
  const preview = reference.locator("[data-api-reference-preview]")

  await trigger.focus()
  await page.locator("h1").hover()
  await expect(preview).toBeVisible()
  await page.locator("h1").dispatchEvent("pointerdown")
  await expect(preview).toBeHidden()
})

test("clamps and flips previews within the viewport", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 300 })
  const reference = references(page).last()
  const trigger = reference.locator("a[data-api-reference-trigger]")
  const preview = reference.locator("[data-api-reference-preview]")
  await trigger.scrollIntoViewIfNeeded()
  await trigger.focus()

  const bounds = await preview.boundingBox()
  const triggerBounds = await trigger.boundingBox()
  expect(bounds.x).toBeGreaterThanOrEqual(11)
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(379)
  expect(bounds.y).toBeGreaterThanOrEqual(11)
  expect(bounds.y + bounds.height).toBeLessThanOrEqual(289)
  if (
    triggerBounds.y + triggerBounds.height + 8 + bounds.height > 288
    && triggerBounds.y >= bounds.height + 20
  ) {
    expect(bounds.y + bounds.height).toBeLessThanOrEqual(triggerBounds.y)
  }
})

test("does not intercept coarse-pointer link navigation", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" })
  await page.addInitScript(() => {
    const original = window.matchMedia.bind(window)
    window.matchMedia = (query) => query.includes("pointer")
      ? { matches: false, media: query, addEventListener() {}, removeEventListener() {} }
      : original(query)
  })
  await page.reload()
  const trigger = references(page).first().locator("a[data-api-reference-trigger]")
  const destination = await trigger.getAttribute("href")

  await trigger.click()
  await expect(page).toHaveURL(new RegExp(`${destination.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}$`))
})

test("keeps API links usable without JavaScript", async ({ browser }) => {
  const context = await browser.newContext({ javaScriptEnabled: false })
  const page = await context.newPage()
  await page.goto("/learn/models-and-messages")
  const trigger = references(page).first().locator("a[data-api-reference-trigger]")

  await expect(trigger).toHaveAttribute("href", /.+/)
  await expect(references(page).first().locator("[data-api-reference-preview]")).toBeHidden()
  await context.close()
})
