import { createRequire } from "node:module"

const require = createRequire(import.meta.url)
const playwrightRoot = process.env.PLAYWRIGHT_TEST_NODE_PATH

if (!playwrightRoot) throw new Error("PLAYWRIGHT_TEST_NODE_PATH is not set; enter through nix develop")

const { expect, test } = require(`${playwrightRoot}/playwright/test.js`)

test("traces a shopping cart message, derived model, and keyed row", async ({ page }) => {
  await page.goto("/examples/shopping-cart")
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")

  const example = page.locator('[data-example="shopping-cart"]')
  const inspector = example.locator(".docs-xray")

  await expect(example.locator("[data-cart-item-count]")).toHaveText("0 items")
  await inspector.getByRole("button", { name: "Start tracing" }).click()
  await example.locator("[data-product=coffee]").click()

  const coffee = example.locator("[data-cart-line=coffee]")
  await expect(example.locator("[data-cart-item-count]")).toHaveText("1 item")
  await expect(coffee.locator("[data-cart-quantity]")).toHaveText("1")
  await expect(coffee.locator("[data-cart-subtotal]")).toHaveText("$12.99")
  await expect(example.locator("[data-cart-total]")).toHaveText("$12.99")
  await expect(inspector.locator("[data-xray-interaction]")).toContainText(
    "ShoppingCartExample.Msg",
  )
  await expect(inspector.locator("[data-xray-interaction]")).toContainText("itemCount = 1")
  await expect(inspector.locator('[data-xray-summary-stage="TreeDiff"]')).toBeVisible()
  await expect(inspector.locator('[data-xray-summary-stage="DomDiff"]')).toBeVisible()

  await inspector.getByText("Raw trace").click()
  await expect(inspector.locator('[data-xray-stage="TypedMessage"]').last()).toContainText(
    "product: coffee",
  )
  await expect(inspector.locator('[data-xray-stage="ModelProposed"]').last()).toContainText(
    "total: $12.99",
  )
})
