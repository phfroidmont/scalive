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
  const count = example.locator("[role=status] strong")

  await expect(count).toHaveText("0")
  await expect(inspector).toHaveAttribute("data-xray-enabled", "false")
  await inspector.getByRole("button", { name: "Enable X-ray" }).click()
  await expect(inspector).toHaveAttribute("data-xray-enabled", "true")

  await example.getByRole("button", { name: "Increase" }).click()
  await expect(count).toHaveText("1")

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

  const socketEpochs = await inspector.locator('[data-xray-socket-epoch]').evaluateAll((records) =>
    [...new Set(records.map((record) => record.dataset.xraySocketEpoch).filter(Boolean))],
  )
  expect(socketEpochs.length).toBeGreaterThanOrEqual(2)

  await inspector.getByRole("button", { name: "Clear trace" }).click()
  await expect(inspector.locator('[data-xray-stage]')).toHaveCount(0)
})
