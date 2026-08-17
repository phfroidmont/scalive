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
  await expect(viewer).toHaveAccessibleName("Live Typed counter trace")
  await expect(viewer.locator(".docs-live-trace-capture-status")).toHaveRole("status")
  await expect(viewer.locator(".docs-live-trace-capture-status")).toHaveAttribute("aria-live", "polite")
  await expect(count).toHaveText("0")
  await expect(viewer.locator('[data-trace-provenance="authored"]')).toHaveCount(0)
  await expect(viewer.locator(".docs-live-trace-catalog")).toHaveCount(0)
  await expect(viewer.getByText("Raw trace", { exact: true })).toHaveCount(0)

  await expect(viewer.locator(".docs-live-trace-capture-summary")).toHaveText("No interactions yet")
  await expect(inspectionStatus).toHaveCount(0)
  await expect(tracePanel).toHaveCount(0)
  await viewer.getByRole("button", { name: "Start capture" }).click()
  await expect(viewer).toHaveAttribute("data-live-trace-enabled", "true")
  await expect(viewer.locator(".docs-live-trace-capture-summary")).toHaveText(
    "Use the example controls",
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
  await expect(interactions.first().locator(".docs-live-trace-event-reference")).toHaveText("#1")
  await expect(inspectionStatus).toContainText(
    /Inspecting #1 Increment Triggered by Browser Status Complete Latest/,
  )
  await expect(viewer.getByText("Click", { exact: true })).toHaveCount(0)

  const capturedTrace = viewer.locator('[data-trace-viewer][data-trace-provenance="captured"]')
  await expect(capturedTrace.locator(".docs-trace-evidence summary").first()).toHaveAccessibleName(
    "Show Protocol frame for Send protocol frame",
  )
  await expect(capturedTrace).not.toContainText(/\d+ records?/)
  await expect(capturedTrace.locator(".docs-trace-evidence-record")).toHaveCount(0)
  const resolvedMessage = capturedTrace.locator('[data-trace-evidence="Resolved message"]')
  await expect(resolvedMessage.locator("code.docs-trace-evidence-scala-value")).toHaveText(
    "Msg.Increment",
  )
  await expect(resolvedMessage.locator("code.docs-trace-evidence-scala-value")).toHaveCSS("font-size", "12px")
  await expect(resolvedMessage).not.toContainText("Increase the count")
  await expect(capturedTrace.locator('[data-trace-evidence="Handler started"]')).toHaveCount(0)
  await expect(capturedTrace.locator('[data-trace-evidence="Handler completed"]')).toHaveCount(0)
  const updatedModel = capturedTrace.locator('[data-trace-evidence="Updated model"]')
  await expect(updatedModel.locator("code.docs-trace-evidence-scala-value")).toHaveText(
    "Model(count = 1)",
  )
  await expect(updatedModel).not.toContainText(/Current counter state|Handler proposed a model|Execution|Correlation/)
  await expect(updatedModel.locator("dl")).toHaveCount(0)
  for (const evidenceLabel of ["Resolved message", "Updated model"]) {
    const evidence = capturedTrace.locator(`[data-trace-evidence="${evidenceLabel}"]`)
    const step = evidence.locator("..")
    const copyBox = await step.locator(":scope > .docs-trace-event-copy").boundingBox()
    const evidenceBox = await evidence.boundingBox()
    expect(Math.abs(copyBox.x + copyBox.width / 2 - (evidenceBox.x + evidenceBox.width / 2))).toBeLessThan(2)
  }
  await expect(
    capturedTrace.locator("[data-trace-step]", { hasText: "Client event" }).locator("[data-trace-evidence]"),
  ).toHaveCount(0)
  await expect(capturedTrace.locator("[data-trace-step]", { hasText: "Compute tree diff" })).toContainText(
    "The rendered tree contains changes.",
  )
  await expect(capturedTrace.locator('[data-trace-evidence="DOM changes"]')).toContainText(
    /mutations|DOM patch applied/,
  )
  await expect(capturedTrace).toContainText('"topic" : "lv:docs-example-counter-')
  await expect(capturedTrace).toContainText('"value" : ""')
  await expect(capturedTrace).toContainText('"target" : "')
  await expect(capturedTrace).toContainText('"1" : "1"')
  await expect(capturedTrace).not.toContainText('"after"')

  await expect(capturedTrace.locator('[data-trace-evidence="Protocol frame"]')).toHaveCount(2)
  const publishStep = capturedTrace.locator("[data-trace-step]", { hasText: "Publish result" })
  const frameDetail = publishStep.locator('[data-trace-evidence="Protocol frame"]')
  await expect(frameDetail.locator(":scope > summary")).toContainText("143 B")
  await frameDetail.locator(":scope > summary").click()
  const frameCode = frameDetail.locator(".docs-trace-evidence-code")
  await expect(frameCode).toBeVisible()
  await expect(frameDetail).not.toContainText("FinalFrame")
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

  await example.getByRole("button", { name: "Decrease", exact: true }).click()
  await expect(count).toHaveText("0")
  await expect(interactions).toHaveCount(2)
  await expect(interactions.first()).toHaveAttribute("data-trace-state", "complete")
  await expect(viewer.locator(".docs-live-trace-capture-summary")).toHaveText(
    "2 interactions retained",
  )
  await expect(interactions.first().locator(".docs-live-trace-event-reference")).toHaveText("#2")
  await expect(interactions.last().locator(".docs-live-trace-event-reference")).toHaveText("#1")

  const olderInteraction = interactions.last()
  const olderId = await olderInteraction.getAttribute("data-trace-interaction")
  await viewer.locator(".docs-live-trace-event-window").evaluate((element) => {
    element.style.maxHeight = "4rem"
  })
  await olderInteraction.click()
  await expect(viewer.locator(`[data-trace-interaction="${olderId}"]`)).toHaveAttribute(
    "aria-pressed",
    "true",
  )
  await expect(tracePanel).toHaveAttribute("aria-labelledby", await olderInteraction.getAttribute("id"))

  await example.getByRole("button", { name: "Increase", exact: true }).click()
  await expect(count).toHaveText("1")
  await expect(interactions).toHaveCount(3)
  await expect(interactions.first().locator(".docs-live-trace-event-reference")).toHaveText("#3")
  await expect(viewer.locator(`[data-trace-interaction="${olderId}"] .docs-live-trace-event-reference`)).toHaveText(
    "#1",
  )
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
  await expect
    .poll(() =>
      interactions.first().evaluate((row) => {
        const viewport = row.closest(".docs-live-trace-event-window")
        const rowRect = row.getBoundingClientRect()
        const viewportRect = viewport.getBoundingClientRect()
        return rowRect.top >= viewportRect.top && rowRect.bottom <= viewportRect.bottom
      }),
    )
    .toBe(true)
  await viewer.locator(".docs-live-trace-event-window").evaluate((element) => {
    element.style.removeProperty("max-height")
  })

  await page.evaluate(() => window.liveSocket.disconnect())
  await page.evaluate(() => window.liveSocket.connect())
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")
  await expect(viewer).toHaveAttribute("data-live-trace-enabled", "true")
  await expect(interactions).toHaveCount(5)
  await expect(interactions.first().locator(".docs-live-trace-event-reference")).toHaveText("#5")

  await viewer.getByRole("button", { name: "Pause capture" }).click()
  await expect(viewer).toHaveAttribute("data-live-trace-enabled", "false")
  const countBeforePausedClick = Number(await count.textContent())
  await example.getByRole("button", { name: "Increase", exact: true }).click()
  await expect(count).toHaveText(String(countBeforePausedClick + 1))
  await expect(interactions).toHaveCount(5)
  await viewer.getByRole("button", { name: "Resume capture" }).click()
  await expect(viewer).toHaveAttribute("data-live-trace-enabled", "true")

  await page.setViewportSize({ width: 390, height: 844 })
  await expect(viewer).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)).toBe(false)

  await viewer.getByRole("button", { name: "Clear", exact: true }).click()
  await expect(interactions).toHaveCount(0)
  await expect(viewer.locator('[data-trace-provenance="captured"]')).toHaveCount(0)
  await expect(tracePanel).toHaveCount(0)
  await expect(viewer.locator(".docs-live-trace-capture-summary")).toHaveText(
    "Use the example controls",
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
  await expect(trace.locator('[data-trace-evidence="Response processed"]')).toHaveCount(0)
  await expect(trace.locator("[data-trace-step]", { hasText: "Publish result" })).toBeAttached()
  await expect(trace.locator('[data-trace-evidence="DOM changes"]')).toHaveCount(0)
  await expect(viewer.locator(".docs-live-trace-panel")).toHaveAttribute("aria-busy", "false")
})

test("keeps interaction numbers monotonic after older records are evicted", async ({ page }) => {
  await page.goto("/examples/counter")
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")

  const example = page.locator('[data-example="counter"]')
  const viewer = example.locator('[data-live-trace-viewer="counter"]')
  const count = example.locator(".docs-counter [role=status] strong")
  const interactions = viewer.locator("[data-trace-interaction]")

  await viewer.getByRole("button", { name: "Start capture" }).click()
  for (let ordinal = 1; ordinal <= 12; ordinal += 1) {
    await example.getByRole("button", { name: "Increase", exact: true }).click()
    await expect(count).toHaveText(String(ordinal))
    await expect(interactions.first()).toHaveAttribute("data-trace-state", "complete")
    await expect(interactions.first().locator(".docs-live-trace-event-reference")).toHaveText(
      `#${ordinal}`,
    )
  }

  expect(await interactions.count()).toBeLessThan(12)
  await page.evaluate(() => window.liveSocket.disconnect())
  await page.evaluate(() => window.liveSocket.connect())
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")
  await expect(interactions.first().locator(".docs-live-trace-event-reference")).toHaveText("#14")

  await viewer.getByRole("button", { name: "Clear", exact: true }).click()
  await expect(interactions).toHaveCount(0)
  await example.getByRole("button", { name: "Increase", exact: true }).click()
  await expect(interactions.first().locator(".docs-live-trace-event-reference")).toHaveText("#1")
})
