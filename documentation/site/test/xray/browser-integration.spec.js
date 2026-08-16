import { createRequire } from "node:module"

const require = createRequire(import.meta.url)
const playwrightRoot = process.env.PLAYWRIGHT_TEST_NODE_PATH

if (!playwrightRoot) throw new Error("PLAYWRIGHT_TEST_NODE_PATH is not set; enter through nix develop")

const { expect, test } = require(`${playwrightRoot}/playwright/test.js`)

test("runs client commands and exposes public typed browser events", async ({ page }) => {
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

  await inspector.getByRole("button", { name: "Start capture" }).click()
  await example.getByRole("button", { name: "Copy sample text" }).click()
  await expect(status).toHaveText("Browser operation completed.")
  expect(await page.evaluate(() => window.__copiedText)).toBe(
    "Scalive keeps server-to-browser event payloads typed.",
  )

  await expect(inspector.locator("[data-trace-interaction]")).toHaveCount(2)
  expect(await inspector.textContent()).toContain("BrowserInteropExample.Msg")
  await inspector.getByRole("button", { name: "Jump to latest" }).click()
  await expect(inspector.locator('[data-trace-evidence="Proposed model"]')).toContainText(
    /operation\s*succeeded/,
  )
  expect(await inspector.textContent()).not.toContain(
    "Scalive keeps server-to-browser event payloads typed.",
  )

  await example.getByRole("button", { name: "Reset browser integration" }).click()
  await expect(status).toHaveText("No browser operation requested yet.")
  await expect(panel).toBeHidden()
  await expect(detail).toBeHidden()
  await expect(placeholder).toBeVisible()
})
