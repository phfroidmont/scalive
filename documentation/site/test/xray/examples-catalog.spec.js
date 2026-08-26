import { expect, test } from "./playwright.js"

test("filters the bounded example catalog through URL patches", async ({ page }) => {
  await page.goto("/examples")
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")
  const initialCount = await page.locator("[data-example-card]").count()
  expect(initialCount).toBeGreaterThan(1)
  await expect(page.getByRole("heading", { name: "Start here", exact: true })).toBeVisible()
  await expect(page.getByRole("heading", { name: "Complete applications" })).toBeVisible()
  await expect(page.locator(".docs-example, [data-example-child], [data-trace-viewer-child]")).toHaveCount(0)

  await page.getByText("Browse all topics", { exact: true }).click()
  await page.getByRole("link", { name: "Keyed rendering", exact: true }).first().click()
  await expect(page).toHaveURL("/examples?topic=keyed-rendering")
  await expect(page.locator("[data-example-card]")).toHaveCount(1)
  await expect(page.locator("[data-example-card=shopping-cart]")).toBeVisible()
  await expect(page.locator("[data-example-topic-filter=keyed-rendering]")).toHaveAttribute(
    "aria-current",
    "page",
  )

  await page.getByText("Keyed rendering", { exact: true }).first().click()
  await page.getByRole("link", { name: "All topics" }).click()
  await expect(page).toHaveURL("/examples")
  await expect(page.locator("[data-example-card]")).toHaveCount(initialCount)
})
