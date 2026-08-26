import { expect, test } from "./playwright.js"

test("identifies browser and runtime initiators for managed async work", async ({ page }) => {
  await page.goto("/examples/async-report")
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")

  const example = page.locator('[data-example="async-report"]')
  const viewer = example.locator('[data-live-trace-viewer="async-report"]')
  const interactions = viewer.locator("[data-trace-interaction]")

  await viewer.getByRole("button", { name: "Start capture" }).click()
  await example.getByRole("button", { name: "Run successful report" }).click()

  await expect(example.locator("[data-report-status]")).toHaveText("Loading")
  await expect(interactions).toHaveCount(1)
  await expect(interactions.first()).toHaveAttribute("data-trace-initiator", "browser")
  await expect(interactions.first().locator(".docs-live-trace-event-initiator")).toHaveText(
    "Triggered by Browser",
  )

  await expect(example.locator("[data-report-status]")).toHaveText("Succeeded")
  await expect(interactions).toHaveCount(2)
  await expect(interactions.first()).toHaveAttribute("data-trace-initiator", "runtime")
  await expect(interactions.first().locator(".docs-live-trace-event-initiator")).toHaveText(
    "Triggered by Scalive runtime",
  )
  await interactions.first().click()
  await expect(viewer.locator(".docs-live-trace-inspection-status")).toContainText(
    "Triggered by Scalive runtime",
  )
})

test("identifies the component instance that emitted an output", async ({ page }) => {
  await page.goto("/examples/voting-components")
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")

  const example = page.locator('[data-example="voting-components"]')
  const viewer = example.locator('[data-live-trace-viewer="voting-components"]')
  const interactions = viewer.locator("[data-trace-interaction]")

  await viewer.getByRole("button", { name: "Start capture" }).click()
  await example.locator('[data-vote-component="scala-vote"]').getByRole("button", { name: "Vote" }).click()

  await expect(interactions).toHaveCount(2)
  await expect(interactions.first()).toHaveAttribute("data-trace-initiator", "component")
  await expect(interactions.first().locator(".docs-live-trace-event-initiator")).toHaveText(
    "Triggered by VoteComponent (scala-vote)",
  )
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)).toBe(false)
})
