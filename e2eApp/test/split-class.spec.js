import { expect, test } from "@playwright/test"

let pageErrors

test.beforeEach(async ({ page }) => {
  pageErrors = []
  page.on("pageerror", error => pageErrors.push(error.message))

  await page.goto("/split-class")
  await expect(page.locator(".phx-connected")).toBeAttached({ timeout: 15_000 })
})

test.afterEach(() => {
  expect(pageErrors).toEqual([])
})

// The static class tokens stay in the template and a diff sends only the dynamic ones, so the
// browser has to join them back into the same class the first render had.
test("joins static and dynamic class tokens on join, insert and update", async ({ page }) => {
  await expect(page.locator("#row-1")).toHaveAttribute("class", "card big selected")
  await expect(page.locator("#banner")).toHaveAttribute("class", "banner")

  await page.locator("#add").click()
  await expect(page.locator("#row-2")).toHaveAttribute("class", "card big")

  await page.locator("#toggle-2").click()
  await expect(page.locator("#row-2")).toHaveAttribute("class", "card big selected")
  await page.locator("#toggle-1").click()
  await expect(page.locator("#row-1")).toHaveAttribute("class", "card big")

  await page.locator("#activate").click()
  await expect(page.locator("#banner")).toHaveAttribute("class", "banner active")
})
