import { createRequire } from "node:module"

const require = createRequire(import.meta.url)
const playwrightRoot = process.env.PLAYWRIGHT_TEST_NODE_PATH

if (!playwrightRoot) throw new Error("PLAYWRIGHT_TEST_NODE_PATH is not set; enter through nix develop")

const { expect, test } = require(`${playwrightRoot}/playwright/test.js`)

test("validates, saves, resets, and redacts a typed profile form", async ({ page }) => {
  await page.goto("/examples/profile-form")
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")

  const example = page.locator('[data-example="profile-form"]')
  const inspector = example.locator('[data-live-trace-viewer="profile-form"]')
  const form = example.locator("[data-profile-form]")

  await inspector.getByRole("button", { name: "Start capture" }).click()
  await form.getByLabel("Name").fill("Ada Lovelace")
  await form.getByLabel("Email").fill("not-an-email")
  await form.getByLabel("Biography").fill("Analytical engine pioneer.")
  await form.getByRole("button", { name: "Save profile" }).click()

  await expect(example.locator("[data-field-error=email] .form-error")).toHaveText(
    "Enter a valid email address.",
  )
  await expect(example.locator("[data-profile-saved]")).toHaveCount(0)

  await form.getByLabel("Email").fill("ada@example.com")
  await form.getByRole("button", { name: "Save profile" }).click()
  await expect(example.locator("[data-profile-saved]")).toHaveText("Saved Ada Lovelace's profile.")
  const jumpToLatest = inspector.getByRole("button", { name: "Jump to latest" })
  if (await jumpToLatest.isVisible()) await jumpToLatest.click()
  const capturedTrace = inspector.locator('[data-trace-provenance="captured"]')
  await expect(capturedTrace.locator('[data-trace-evidence="Updated model"]')).toContainText(
    /saved\s*true/,
  )
  const projectedTrace = await capturedTrace.textContent()
  expect(projectedTrace).not.toContain("ada@example.com")
  expect(projectedTrace).not.toContain("Analytical engine pioneer.")

  await form.getByRole("button", { name: "Reset form" }).click()
  await expect(form.getByLabel("Name")).toHaveValue("")
  await expect(example.locator("[data-profile-saved]")).toHaveCount(0)
})
