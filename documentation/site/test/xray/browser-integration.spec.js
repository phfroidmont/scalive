import { expect, test } from "./playwright.js"

const copyChunk = /\/static\/chunks\/browser-copy-[^/]+\.js$/

async function recordClipboardWrites(page) {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: {
        async writeText(text) {
          window.__copiedText = text
        },
      },
    })
  })
}

test("downloads the hook's module only on a page that renders the hook", async ({ page }) => {
  const chunkRequests = []
  page.on("request", (request) => {
    if (copyChunk.test(new URL(request.url()).pathname)) chunkRequests.push(request.url())
  })
  await recordClipboardWrites(page)

  await page.goto("/examples/counter")
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")
  expect(chunkRequests).toEqual([])

  await page.goto("/examples/browser-integration")
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")
  const example = page.locator('[data-example="browser-integration"]')
  await example.getByRole("button", { name: "Copy sample text" }).click()
  await expect(example.locator("[data-browser-copy-status]")).toHaveText(
    "Browser operation completed.",
  )
  expect(await page.evaluate(() => window.__copiedText)).toBe(
    "Scalive keeps server-to-browser event payloads typed.",
  )
  await expect(example.locator("[data-browser-hook-failed]")).toBeHidden()
  expect(chunkRequests).toHaveLength(1)
})

test("tells the user when the hook's module cannot load", async ({ page }) => {
  await page.route(copyChunk, (route) => route.abort())
  await page.goto("/examples/browser-integration")
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")

  const example = page.locator('[data-example="browser-integration"]')
  const failed = example.locator("[data-browser-hook-failed]")
  await expect(failed).toBeVisible()
  await expect(failed).toHaveText("Copying could not load. Reload the page to try again.")

  await example.getByRole("button", { name: "Copy sample text" }).click()
  await expect(example.locator("[data-browser-copy-status]")).toHaveText(
    "Waiting for the browser result. Retry if needed.",
  )
  await expect(failed).toBeVisible()
})

test("runs client commands and exposes public typed browser events", async ({ page }) => {
  await recordClipboardWrites(page)
  await page.goto("/examples/browser-integration")
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")

  const example = page.locator('[data-example="browser-integration"]')
  const inspector = example.locator('[data-live-trace-viewer="browser-integration"]')
  const rendered = example.locator(".docs-browser-integration")
  const panel = rendered.locator('[id$="-panel"]')
  const placeholder = rendered.locator('[id$="-placeholder"]')
  const detail = rendered.locator('[id$="-detail"]')
  const status = example.locator("[data-browser-copy-status]")

  await expect(panel).toBeHidden()
  await expect(placeholder).toBeVisible()
  await example.getByRole("button", { name: "Run composed command" }).click()
  await expect(panel).toBeVisible()
  await expect(placeholder).toBeHidden()
  await expect(detail).toBeVisible()

  await inspector.getByRole("button", { name: "Inspect live interactions" }).click()
  await example.getByRole("button", { name: "Copy sample text" }).click()
  await expect(status).toHaveText("Browser operation completed.")
  expect(await page.evaluate(() => window.__copiedText)).toBe(
    "Scalive keeps server-to-browser event payloads typed.",
  )

  await expect(inspector.locator("[data-trace-interaction]")).toHaveCount(2)
  await expect(inspector.locator("[data-trace-interaction]").first()).toHaveAttribute(
    "aria-pressed",
    "true",
  )
  const modelValue = inspector.locator('[data-trace-evidence="Updated model"] code')
  await expect(modelValue).toContainText("Model(")
  await expect(modelValue).toContainText("requestNumber = 1")
  await expect(modelValue).toContainText("operation = CopyOperation.Succeeded")
  await expect(modelValue).not.toContainText("BrowserInteropExample")
  expect(await inspector.textContent()).not.toContain(
    "Scalive keeps server-to-browser event payloads typed.",
  )

  await example.getByRole("button", { name: "Reset browser integration" }).click()
  await expect(status).toHaveText("No browser operation requested yet.")
  await expect(panel).toBeHidden()
  await expect(detail).toBeHidden()
  await expect(placeholder).toBeVisible()
})
