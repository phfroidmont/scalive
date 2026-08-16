import { createRequire } from "node:module"

const require = createRequire(import.meta.url)
const playwrightRoot = process.env.PLAYWRIGHT_TEST_NODE_PATH

if (!playwrightRoot) throw new Error("PLAYWRIGHT_TEST_NODE_PATH is not set; enter through nix develop")

const { expect, test } = require(`${playwrightRoot}/playwright/test.js`)

test("traces a shopping cart message, derived model, and keyed row", async ({ page }) => {
  await page.goto("/examples/shopping-cart")
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")

  const example = page.locator('[data-example="shopping-cart"]')
  const inspector = example.locator('[data-live-trace-viewer="shopping-cart"]')

  await expect(example.locator("[data-cart-item-count]")).toHaveText("0 items")
  await inspector.getByRole("button", { name: "Start capture" }).click()
  await example.locator("[data-product=coffee]").click()

  const coffee = example.locator("[data-cart-line=coffee]")
  await expect(example.locator("[data-cart-item-count]")).toHaveText("1 item")
  await expect(coffee.locator("[data-cart-quantity]")).toHaveText("1")
  await expect(coffee.locator("[data-cart-subtotal]")).toHaveText("$12.99")
  await expect(example.locator("[data-cart-total]")).toHaveText("$12.99")
  await expect(inspector.locator("[data-trace-interaction]")).toHaveCount(1)
  await expect(inspector.locator('[data-trace-evidence="Tree diff"]')).toBeAttached()
  await expect(inspector.locator('[data-trace-evidence="DOM mutations"]')).toBeAttached()
  await expect(inspector.locator('[data-trace-evidence="Typed message"]')).toContainText(
    "product: coffee",
  )
  await expect(inspector.locator('[data-trace-evidence="Proposed model"]')).toContainText(
    "total: $12.99",
  )
})
