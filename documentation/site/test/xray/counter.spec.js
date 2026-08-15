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
  const inspectionStatus = viewer.locator(".docs-live-trace-inspection-status")
  const tracePanel = viewer.locator(".docs-live-trace-panel")

  await expect(viewer).toHaveRole("region")
  await expect(viewer).toHaveAccessibleName("Live counter trace")
  await expect(viewer.locator(".docs-live-trace-capture-status")).toHaveRole("status")
  await expect(viewer.locator(".docs-live-trace-capture-status")).toHaveAttribute("aria-live", "polite")
  await expect(count).toHaveText("0")
  await expect(viewer.locator('[data-trace-provenance="authored"]')).toHaveCount(0)
  await expect(viewer.locator(".docs-live-trace-catalog")).toHaveCount(0)
  await expect(viewer.locator(".docs-xray-raw")).toHaveCount(0)
  await expect(viewer.getByText("Raw trace", { exact: true })).toHaveCount(0)

  await expect(viewer.locator(".docs-live-trace-capture-summary")).toHaveText("No interactions yet")
  await expect(inspectionStatus).toHaveCount(0)
  await expect(tracePanel).toHaveCount(0)
  await viewer.getByRole("button", { name: "Start capture" }).click()
  await expect(viewer).toHaveAttribute("data-xray-enabled", "true")
  await expect(viewer.locator(".docs-live-trace-capture-summary")).toHaveText(
    "Use a counter control",
  )

  await example.getByRole("button", { name: "Increase", exact: true }).click()
  await expect(count).toHaveText("1")
  await expect(interactions).toHaveCount(1)
  await expect(interactions.first()).toHaveAttribute("aria-pressed", "true")
  await expect(inspectionStatus).toHaveRole("status")
  await expect(tracePanel).toHaveRole("region")
  await expect(viewer.locator(".docs-live-trace-capture-summary")).toHaveText(
    "1 interaction retained",
  )
  await expect(inspectionStatus).toContainText(
    /Inspecting #[^/]+ \/ Increase counter \/ Complete \/ latest/,
  )
  await expect(viewer.getByText("Click", { exact: true })).toHaveCount(0)

  const capturedTrace = viewer.locator('[data-trace-viewer][data-trace-provenance="captured"]')
  await expect(capturedTrace.locator(".docs-trace-evidence summary").first()).toHaveAccessibleName(
    /Show technical \d+ records? for /,
  )
  await expect(capturedTrace.locator('[data-trace-evidence="Typed message"]')).toContainText(
    "CounterExample.Msg.Increment",
  )
  await expect(capturedTrace.locator('[data-trace-evidence="Proposed model"]')).toContainText(
    /count\s*1/,
  )
  await expect(capturedTrace.locator('[data-trace-evidence="Tree diff"]')).toContainText(
    "Tree diff contains changes",
  )
  await expect(capturedTrace.locator('[data-trace-evidence="DOM mutations"]')).toContainText(
    /mutations|DOM patch applied/,
  )
  await expect(capturedTrace).toContainText('"topic" : "lv:docs-example-counter-')
  await expect(capturedTrace).toContainText('"after" : "1"')

  const frameRecord = capturedTrace.locator('[data-trace-evidence="Final frame"]')
  await expect(frameRecord.locator(":scope > details > summary")).toContainText("Runtime")
  await expect(frameRecord.locator(":scope > details > summary")).toContainText("143 B")
  await expect(capturedTrace.locator('[data-trace-evidence="Inbound frame"] > details > summary')).toContainText(
    "Browser",
  )
  const frameGroup = frameRecord.locator("xpath=ancestor::details[contains(@class, 'docs-trace-evidence')]")
  await frameGroup.locator(":scope > summary").click()
  await frameRecord.locator(":scope > details > summary").click()
  const frameCode = frameRecord.locator(".docs-trace-evidence-code")
  await expect(frameCode).toBeVisible()
  await expect(frameRecord).not.toContainText("FinalFrame")
  const wrapLines = frameCode.locator("[data-trace-code-wrap]")
  await wrapLines.click()
  await expect(wrapLines).toHaveAttribute("aria-pressed", "true")
  const showAll = frameCode.locator("[data-trace-code-expand]")
  await showAll.click()
  await expect(showAll).toHaveAttribute("aria-expanded", "true")
  await expect(interactions.first()).toHaveAttribute("data-trace-state", "complete")
  const selectedRowId = await interactions.first().getAttribute("id")
  const tracePanelId = await tracePanel.getAttribute("id")
  await expect(interactions.first()).toHaveAttribute("aria-controls", tracePanelId)
  await expect(tracePanel).toHaveAttribute("aria-labelledby", selectedRowId)
  await expect(tracePanel).toHaveAttribute("aria-busy", "false")

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
  await expect(viewer.locator(".docs-live-trace-capture-summary")).toHaveText(
    "2 interactions retained",
  )

  const olderInteraction = interactions.last()
  const olderId = await olderInteraction.getAttribute("data-trace-interaction")
  await olderInteraction.click()
  await expect(viewer.locator(`[data-trace-interaction="${olderId}"]`)).toHaveAttribute(
    "aria-pressed",
    "true",
  )
  await expect(tracePanel).toHaveAttribute("aria-labelledby", await olderInteraction.getAttribute("id"))

  await example.getByRole("button", { name: "Increase", exact: true }).click()
  await expect(count).toHaveText("1")
  await expect(interactions).toHaveCount(3)
  await expect(viewer.locator(`[data-trace-interaction="${olderId}"]`)).toHaveAttribute(
    "aria-pressed",
    "true",
  )
  await expect(tracePanel).toHaveAttribute("aria-labelledby", await olderInteraction.getAttribute("id"))
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
  await expect(tracePanel).toHaveCount(0)
  await expect(viewer.locator(".docs-live-trace-capture-summary")).toHaveText(
    "Use a counter control",
  )
})

test("completes a no-op counter interaction without waiting for DOM mutations", async ({ page }) => {
  await page.goto("/examples/counter")
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")

  const example = page.locator('[data-example="counter"]')
  const viewer = example.locator('[data-live-trace-viewer="counter"]')
  const interactions = viewer.locator("[data-trace-interaction]")

  await viewer.getByRole("button", { name: "Start capture" }).click()
  await example.getByRole("button", { name: "Reset", exact: true }).click()

  await expect(example.locator(".docs-counter [role=status] strong")).toHaveText("0")
  await expect(interactions).toHaveCount(1)
  await expect(interactions.first()).toHaveAttribute("data-trace-state", "complete")
  await expect(interactions.first()).toHaveAttribute("aria-busy", "false")

  const trace = viewer.locator('[data-trace-provenance="captured"]')
  await expect(trace.locator('[data-trace-evidence="Response processed"]')).toBeAttached()
  await expect(trace.locator('[data-trace-evidence="DOM mutations"]')).toHaveCount(0)
  await expect(viewer.locator(".docs-live-trace-panel")).toHaveAttribute("aria-busy", "false")
})
