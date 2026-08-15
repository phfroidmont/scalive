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
  const inspector = example.locator(".docs-xray")
  const panel = example.locator('[id$="-panel"]')
  const placeholder = example.locator('[id$="-placeholder"]')
  const detail = example.locator('[id$="-detail"]')
  const status = example.locator("[data-browser-copy-status]")

  await expect(panel).toBeHidden()
  await expect(placeholder).toBeVisible()
  await example.getByRole("button", { name: "Run composed command" }).click()
  await expect(panel).toBeVisible()
  await expect(placeholder).toBeHidden()
  await expect(detail).toBeVisible()

  await inspector.getByRole("button", { name: "Start tracing" }).click()
  await example.getByRole("button", { name: "Copy sample text" }).click()
  await expect(status).toHaveText("Browser operation completed.")
  expect(await page.evaluate(() => window.__copiedText)).toBe(
    "Scalive keeps server-to-browser event payloads typed.",
  )

  await inspector.getByText("Raw trace").click()
  const traceText = await inspector.textContent()
  expect(traceText).toContain("BrowserInteropExample.Msg")
  expect(traceText).toContain("operation: succeeded")
  expect(traceText).toContain("Scalive keeps server-to-browser event payloads typed.")

  await example.getByRole("button", { name: "Reset browser integration" }).click()
  await expect(status).toHaveText("No browser operation requested yet.")
  await expect(panel).toBeHidden()
  await expect(detail).toBeHidden()
  await expect(placeholder).toBeVisible()
})
