import { createRequire } from "node:module"

const require = createRequire(import.meta.url)
const playwrightRoot = process.env.PLAYWRIGHT_TEST_NODE_PATH

if (!playwrightRoot) throw new Error("PLAYWRIGHT_TEST_NODE_PATH is not set; enter through nix develop")

const { expect, test } = require(`${playwrightRoot}/playwright/test.js`)

test("captures counter interactions in the integrated live trace viewer", async ({ page }) => {
  await page.goto("/examples/counter")
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")

  const example = page.locator('[data-example="counter"]')
  const viewer = example.locator('[data-live-trace-viewer="counter"]')
  const count = example.locator(".docs-counter [role=status] strong")
  const interactions = viewer.locator("[data-trace-interaction]")

  await expect(count).toHaveText("0")
  await expect(viewer.locator('[data-trace-provenance="authored"]')).toHaveCount(0)
  await expect(viewer.locator(".docs-live-trace-catalog")).toHaveCount(0)
  await expect(viewer.locator(".docs-xray-raw")).toHaveCount(0)
  await expect(viewer.getByText("Raw trace", { exact: true })).toHaveCount(0)

  await expect(viewer).toContainText("No captured interaction")
  await viewer.getByRole("button", { name: "Start capture" }).click()
  await expect(viewer).toHaveAttribute("data-xray-enabled", "true")

  await example.getByRole("button", { name: "Increase", exact: true }).click()
  await expect(count).toHaveText("1")
  await expect(interactions).toHaveCount(1)
  await expect(interactions.first()).toHaveAttribute("aria-pressed", "true")

  const capturedTrace = viewer.locator('[data-trace-viewer][data-trace-provenance="captured"]')
  await expect(capturedTrace.locator('[data-trace-evidence="Typed message"]')).toContainText(
    "CounterExample.Msg.Increment",
  )
  await expect(capturedTrace.locator('[data-trace-evidence="Proposed model"]')).toContainText(
    /count\s*1/,
  )
  await expect(capturedTrace.locator('[data-trace-evidence="Tree diff"]')).toContainText(
    "TreeDiff",
  )
  await expect(capturedTrace.locator('[data-trace-evidence="DOM mutations"]')).toContainText(
    /mutations|DOM patch applied/,
  )
  await expect(capturedTrace).toContainText("[redacted]")
  await expect(interactions.first()).toHaveAttribute("data-trace-state", "complete")

  const csrfToken = await page.locator('meta[name="csrf-token"]').getAttribute("content")
  const exampleSession = await example.locator("[data-phx-session]").first().getAttribute("data-phx-session")
  const capturedText = await capturedTrace.textContent()
  expect(capturedText).not.toContain(csrfToken)
  expect(capturedText).not.toContain(exampleSession)
  await expect(viewer.locator(".docs-xray-raw")).toHaveCount(0)

  await example.getByRole("button", { name: "Decrease", exact: true }).click()
  await expect(count).toHaveText("0")
  await expect(interactions).toHaveCount(2)
  await expect(interactions.first()).toHaveAttribute("data-trace-state", "complete")

  const olderInteraction = interactions.last()
  const olderId = await olderInteraction.getAttribute("data-trace-interaction")
  await olderInteraction.click()
  await expect(viewer.locator(`[data-trace-interaction="${olderId}"]`)).toHaveAttribute(
    "aria-pressed",
    "true",
  )

  await example.getByRole("button", { name: "Increase", exact: true }).click()
  await expect(count).toHaveText("1")
  await expect(interactions).toHaveCount(3)
  await expect(viewer.locator(`[data-trace-interaction="${olderId}"]`)).toHaveAttribute(
    "aria-pressed",
    "true",
  )
  await expect(viewer.locator(".docs-live-trace-inspection")).toContainText(/[1-9]\d* newer/)
  await viewer.getByRole("button", { name: "Jump to latest" }).click()
  await expect(viewer.locator(`[data-trace-interaction="${olderId}"]`)).toHaveAttribute(
    "aria-pressed",
    "false",
  )
  await expect(interactions.first()).toHaveAttribute("aria-pressed", "true")

  await page.evaluate(() => window.liveSocket.disconnect())
  await page.evaluate(() => window.liveSocket.connect())
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")
  await expect(viewer).toHaveAttribute("data-xray-enabled", "true")
  await expect(interactions).toHaveCount(3)

  await viewer.getByRole("button", { name: "Pause capture" }).click()
  await expect(viewer).toHaveAttribute("data-xray-enabled", "false")
  const countBeforePausedClick = Number(await count.textContent())
  await example.getByRole("button", { name: "Increase", exact: true }).click()
  await expect(count).toHaveText(String(countBeforePausedClick + 1))
  await expect(interactions).toHaveCount(3)
  await viewer.getByRole("button", { name: "Resume capture" }).click()
  await expect(viewer).toHaveAttribute("data-xray-enabled", "true")

  await page.setViewportSize({ width: 390, height: 844 })
  await expect(viewer).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)).toBe(false)

  await viewer.getByRole("button", { name: "Clear", exact: true }).click()
  await expect(interactions).toHaveCount(0)
  await expect(viewer.locator('[data-trace-provenance="captured"]')).toHaveCount(0)
  await expect(viewer).toContainText("Use a counter control to capture an interaction.")
})
