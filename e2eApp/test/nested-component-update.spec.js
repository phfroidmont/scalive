import { expect, test } from "@playwright/test"

let pageErrors

test.beforeEach(async ({ page }) => {
  pageErrors = []
  page.on("pageerror", error => pageErrors.push(error.message))

  await page.goto("/nested-component-update")
  await expect(page.locator(".phx-connected")).toBeAttached({ timeout: 15_000 })
})

test.afterEach(() => {
  expect(pageErrors).toEqual([])
})

// The browser skips a component a diff does not reach and keeps what is in the page, so a diff
// that changes the page has to reach the unchanged component around one that changed.
test("updates a component nested in an unchanged one when the page changes too", async ({ page }) => {
  await page.locator("#bump").click()
  await expect(page.locator("#count")).toHaveText("1")
  await expect(page.locator("#outer span")).toHaveText("nested 1")

  await page.locator("#bump").click()
  await expect(page.locator("#count")).toHaveText("2")
  await expect(page.locator("#outer span")).toHaveText("nested 2")
})
