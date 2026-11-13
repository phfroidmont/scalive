import { expect, test } from "@playwright/test"

let pageErrors

test.beforeEach(async ({ page }) => {
  pageErrors = []
  page.on("pageerror", error => pageErrors.push(error.message))

  await page.goto("/lifecycle-push")
  await expect(page.locator(".phx-connected")).toBeAttached({ timeout: 15_000 })
})

test.afterEach(() => {
  expect(pageErrors).toEqual([])
})

// phx-mounted and phx-connected run their JS command with a null event type, so a JS.push in
// them sends an event whose type is null.
test("a JS.push in dom.onMount and connection.onConnect reaches the LiveView", async ({ page }) => {
  await expect(page.locator("#on-mount")).toHaveText("1")
  await expect(page.locator("#on-connect")).toHaveText("1")

  await page.locator("#show-panel").click()
  await expect(page.locator("#panel-mounted")).toHaveText("1")

  // The socket stayed up: no rejoin re-ran the mount and connect commands.
  await expect(page.locator("#on-mount")).toHaveText("1")
  await expect(page.locator("#on-connect")).toHaveText("1")
})
