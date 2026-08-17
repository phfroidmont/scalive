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
  await expect(inspector.locator('[data-trace-evidence="Tree diff"]')).toHaveCount(0)
  await expect(inspector.locator("[data-trace-step]", { hasText: "Compute tree diff" })).toContainText(
    "The rendered tree contains changes.",
  )
  await expect(inspector.locator('[data-trace-evidence="DOM changes"]')).toBeAttached()
  await expect(
    inspector
      .locator('[data-trace-step-kind="message"][data-trace-from="runtime"][data-trace-to="live-view"]')
      .filter({ hasText: "Add" }),
  ).toContainText("Add")
  await expect(inspector.locator('[data-trace-step-kind="operation"][data-trace-participant="live-view"]')).toContainText(
    "Handle Add",
  )
  await expect(inspector.locator('[data-trace-evidence="Resolved message"] code')).toHaveText(
    "Msg.Add(Product.Coffee)",
  )
  const modelValue = inspector.locator('[data-trace-evidence="Updated model"] code')
  await expect(modelValue).toContainText("Model(")
  await expect(modelValue).toContainText("Line(Product.Coffee, quantity = 1)")
  await expect(modelValue).not.toContainText("ShoppingCartExample")
  expect(await modelValue.textContent()).toContain("\n")
})
