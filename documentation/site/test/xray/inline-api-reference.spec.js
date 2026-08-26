import { expect, test } from "./playwright.js"

const references = (page) => page.locator(".docs-inline-api-reference")

test.beforeEach(async ({ page }) => {
  await page.goto("/learn/models-and-messages")
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")
})

test("coordinates pointer, focus, and Escape across inline API references", async ({ page }) => {
  const first = references(page).first()
  const second = references(page).nth(1)
  const firstTrigger = first.locator("a[data-api-reference-trigger]")
  const firstPreview = first.locator("[data-api-reference-preview]")
  const secondTrigger = second.locator("a[data-api-reference-trigger]")
  const secondPreview = second.locator("[data-api-reference-preview]")

  expect(await references(page).count()).toBeGreaterThan(1)
  await firstTrigger.dispatchEvent("pointerover")
  await expect(firstPreview).toBeVisible()
  await expect(firstPreview).toHaveAttribute("id", /docs-api-reference-preview-\d+/)

  await secondTrigger.dispatchEvent("pointerover")
  await expect(secondPreview).toBeVisible()
  await expect(firstPreview).toBeHidden()
  await secondTrigger.evaluate((trigger) => {
    trigger.dispatchEvent(new PointerEvent("pointerout", {
      bubbles: true,
      relatedTarget: document.querySelector("h1"),
    }))
  })
  await expect(secondPreview).toBeHidden()

  await secondTrigger.focus()
  await page.locator("h1").hover()
  await expect(secondPreview).toBeVisible()
  await expect(secondTrigger).toHaveAttribute("aria-describedby", await secondPreview.getAttribute("id"))
  await page.keyboard.press("Escape")
  await expect(secondPreview).toBeHidden()
  await expect(secondTrigger).toBeFocused()
  await expect(secondTrigger).not.toHaveAttribute("aria-describedby")
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
  expect(bounds.x).toBeGreaterThanOrEqual(0)
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(390)
  expect(bounds.y).toBeGreaterThanOrEqual(0)
  expect(bounds.y + bounds.height).toBeLessThanOrEqual(300)
  if (
    triggerBounds.y + triggerBounds.height + bounds.height > 300
    && triggerBounds.y >= bounds.height
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
