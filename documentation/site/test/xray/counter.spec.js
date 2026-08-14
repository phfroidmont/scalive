import { createRequire } from "node:module"

const require = createRequire(import.meta.url)
const playwrightRoot = process.env.PLAYWRIGHT_TEST_NODE_PATH

if (!playwrightRoot) throw new Error("PLAYWRIGHT_TEST_NODE_PATH is not set; enter through nix develop")

const { expect, test } = require(`${playwrightRoot}/playwright/test.js`)

test("correlates a counter click through server and browser application", async ({ page }) => {
  await page.goto("/examples/counter")
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")

  const example = page.locator('[data-example="counter"]')
  const inspector = example.locator(".docs-xray")
  const count = example.locator(".docs-counter [role=status] strong")

  await expect(count).toHaveText("0")
  await expect(inspector).toHaveAttribute("data-xray-enabled", "false")
  await inspector.getByRole("button", { name: "Start tracing" }).click()
  await expect(inspector).toHaveAttribute("data-xray-enabled", "true")

  await example.getByRole("button", { name: "Increase" }).click()
  await expect(count).toHaveText("1")
  await expect(inspector.locator('[data-xray-interaction]')).toContainText("CounterExample.Msg")
  await expect(inspector.locator('[data-xray-interaction]')).toContainText("count = 1")

  const summarySteps = inspector.locator("[data-xray-summary-order]")
  const summaryStages = await summarySteps.evaluateAll((steps) =>
    steps.map((step) => step.dataset.xraySummaryStage),
  )
  expect(summaryStages).toEqual(expect.arrayContaining([
    "BrowserEvent",
    "TypedMessage",
    "ModelProposed",
    "TreeDiff",
    "DomDiff",
  ]))
  expect(summaryStages.indexOf("BrowserEvent")).toBeLessThan(summaryStages.indexOf("TypedMessage"))
  expect(summaryStages.indexOf("TypedMessage")).toBeLessThan(summaryStages.indexOf("ModelProposed"))
  expect(summaryStages.indexOf("ModelProposed")).toBeLessThan(summaryStages.indexOf("TreeDiff"))
  expect(summaryStages.indexOf("TreeDiff")).toBeLessThan(summaryStages.indexOf("DomDiff"))

  await inspector.getByText("Raw trace").click()

  const causalRecords = inspector.locator(".docs-xray-causal-list [data-xray-stage]")
  const causalStages = await causalRecords.evaluateAll((records) =>
    records.map((record) => record.dataset.xrayStage),
  )
  await expect(inspector.locator(".docs-xray-handoff-browser-server")).toContainText(
    "Request to server",
  )
  await expect(inspector.locator(".docs-xray-handoff-server-browser")).toContainText(
    "Response to browser",
  )

  for (const stage of [
    "DecodedEvent",
    "BindingResolution",
    "TypedMessage",
    "ModelProposed",
    "ModelRendered",
    "TreeDiff",
    "ModelCommitted",
    "FinalFrame",
  ]) {
    await expect(inspector.locator(`[data-xray-stage="${stage}"]`).first()).toBeVisible()
  }

  for (const stage of ["BrowserEvent", "OutboundFrame", "InboundFrame", "DomPatch", "DomDiff"]) {
    await expect(inspector.locator(`[data-xray-stage="${stage}"]`).first()).toBeVisible()
  }
  expect(causalStages.indexOf("OutboundFrame")).toBeLessThan(causalStages.indexOf("DecodedEvent"))
  expect(causalStages.indexOf("FinalFrame")).toBeLessThan(causalStages.indexOf("InboundFrame"))
  expect(causalStages.indexOf("InboundFrame")).toBeLessThan(causalStages.indexOf("DomPatch"))

  const serverFrame = inspector.locator('[data-xray-stage="FinalFrame"]').last()
  const browserFrame = inspector.locator('[data-xray-stage="InboundFrame"]').last()
  await expect(serverFrame).toHaveAttribute(
    "data-xray-message-reference",
    await browserFrame.getAttribute("data-xray-message-reference"),
  )

  const csrfToken = await page.locator('meta[name="csrf-token"]').getAttribute("content")
  const exampleSession = await example.locator('[data-phx-session]').first().getAttribute("data-phx-session")
  const inspectorText = await inspector.textContent()
  expect(inspectorText).not.toContain(csrfToken)
  expect(inspectorText).not.toContain(exampleSession)
  expect(inspectorText).toContain("[redacted]")

  await page.evaluate(() => window.liveSocket.disconnect())
  await page.evaluate(() => window.liveSocket.connect())
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")
  await expect(inspector).toHaveAttribute("data-xray-enabled", "true")
  await expect(inspector.locator(".docs-xray-history")).toHaveCount(1)
  await inspector.getByText("Raw trace").click()
  await inspector.locator(".docs-xray-history > summary").click()
  await expect(inspector.locator('.docs-xray-history [data-xray-producer="browser"]')).toHaveCount(1)
  await expect(inspector.locator('.docs-xray-history [data-xray-producer="server"]')).toHaveCount(1)

  const socketEpochs = await inspector.locator('[data-xray-socket-epoch]').evaluateAll((records) =>
    [...new Set(records.map((record) => record.dataset.xraySocketEpoch).filter(Boolean))],
  )
  expect(socketEpochs.length).toBeGreaterThanOrEqual(2)

  await inspector.getByRole("button", { name: "Clear trace" }).click()
  await expect(inspector.locator('[data-xray-stage]')).toHaveCount(0)
})
