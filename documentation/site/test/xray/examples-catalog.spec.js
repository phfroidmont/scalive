import { createRequire } from "node:module"

const require = createRequire(import.meta.url)
const playwrightRoot = process.env.PLAYWRIGHT_TEST_NODE_PATH

if (!playwrightRoot) throw new Error("PLAYWRIGHT_TEST_NODE_PATH is not set; enter through nix develop")

const { expect, test } = require(`${playwrightRoot}/playwright/test.js`)

test("filters the bounded example catalog through URL patches", async ({ page }) => {
  await page.goto("/examples")
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")
  const initialCount = await page.locator("[data-example-card]").count()
  expect(initialCount).toBeGreaterThan(1)
  await expect(page.locator(".docs-example, [data-example-child], [data-inspector-child]")).toHaveCount(0)

  await page.getByRole("link", { name: "Keyed rendering", exact: true }).first().click()
  await expect(page).toHaveURL("/examples?topic=keyed-rendering")
  await expect(page.locator("[data-example-card]")).toHaveCount(1)
  await expect(page.locator("[data-example-card=shopping-cart]")).toBeVisible()
  await expect(page.locator("[data-example-topic-filter=keyed-rendering]")).toHaveAttribute(
    "aria-current",
    "page",
  )

  await page.getByRole("link", { name: "All examples" }).click()
  await expect(page).toHaveURL("/examples")
  await expect(page.locator("[data-example-card]")).toHaveCount(initialCount)
})

test("filters the catalog without JavaScript", async ({ browser }) => {
  const context = await browser.newContext({ javaScriptEnabled: false })
  const page = await context.newPage()

  await page.goto("/examples?topic=keyed-rendering")
  await expect(page.locator("[data-example-card]")).toHaveCount(1)
  await expect(page.locator("[data-example-card=shopping-cart]")).toBeVisible()
  await expect(page.locator(".docs-example, .docs-code-block")).toHaveCount(0)

  await context.close()
})
