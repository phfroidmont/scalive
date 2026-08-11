import { createRequire } from "node:module"

const require = createRequire(import.meta.url)
const playwrightRoot = process.env.PLAYWRIGHT_TEST_NODE_PATH

if (!playwrightRoot) throw new Error("PLAYWRIGHT_TEST_NODE_PATH is not set; enter through nix develop")

const { expect, test } = require(`${playwrightRoot}/playwright/test.js`)

test("correlates a counter click through server and browser application", async ({ page }) => {
  await page.goto("/examples")
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
  await expect(summarySteps).toHaveCount(5)
  expect(
    await summarySteps.evaluateAll((steps) =>
      steps.map((step) => ({
        order: step.dataset.xraySummaryOrder,
        producer: step.dataset.xraySummaryProducer,
        stage: step.dataset.xraySummaryStage,
      })),
    ),
  ).toEqual([
    { order: "1", producer: "browser", stage: "BrowserEvent" },
    { order: "2", producer: "server", stage: "TypedMessage" },
    { order: "3", producer: "server", stage: "ModelProposed" },
    { order: "4", producer: "server", stage: "TreeDiff" },
    { order: "5", producer: "browser", stage: "DomDiff" },
  ])

  await inspector.getByText("Raw trace").click()

  const causalRecords = inspector.locator(".docs-xray-causal-list [data-xray-stage]")
  await expect(causalRecords).toHaveCount(18)
  expect(
    await causalRecords.evaluateAll((records) =>
      records.map((record) => ({
        producer: record.classList.contains("docs-xray-raw-browser") ? "browser" : "server",
        stage: record.dataset.xrayStage,
      })),
    ),
  ).toEqual([
    { producer: "browser", stage: "BrowserEvent" },
    { producer: "browser", stage: "OutboundFrame" },
    { producer: "server", stage: "DecodedEvent" },
    { producer: "server", stage: "DecodedEvent" },
    { producer: "server", stage: "BindingResolution" },
    { producer: "server", stage: "TypedMessage" },
    { producer: "server", stage: "Lifecycle" },
    { producer: "server", stage: "Lifecycle" },
    { producer: "server", stage: "ModelProposed" },
    { producer: "server", stage: "ModelRendered" },
    { producer: "server", stage: "RenderCompleted" },
    { producer: "server", stage: "TreeDiff" },
    { producer: "server", stage: "ModelCommitted" },
    { producer: "server", stage: "FinalPayload" },
    { producer: "server", stage: "FinalFrame" },
    { producer: "browser", stage: "InboundFrame" },
    { producer: "browser", stage: "DomPatch" },
    { producer: "browser", stage: "DomDiff" },
  ])
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
