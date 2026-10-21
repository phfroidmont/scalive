import { expect, test } from "@playwright/test"

let pageErrors

test.beforeEach(async ({ page }) => {
  pageErrors = []
  page.on("pageerror", error => pageErrors.push(error.message))
})

test.afterEach(() => {
  expect(pageErrors).toEqual([])
})

test("live navigation to a missing item ends on the 404 page", async ({ page }) => {
  await page.goto("/items")
  await expect(page.locator(".phx-connected")).toBeAttached({ timeout: 15_000 })

  await page.getByRole("link", { name: "Present" }).click()
  await expect(page.locator("#item")).toHaveText("present")

  await page.goBack()
  await page.getByRole("link", { name: "Missing" }).click()
  await expect(page.locator("#error-status")).toHaveText("Error 404")
  await expect(page).toHaveURL(/\/items\/missing$/)
  await expect(page).toHaveTitle("Error 404")
})

test("an item that vanishes before the join reloads once into the 404 page", async ({ page }) => {
  const statuses = []
  page.on("response", response => {
    if (new URL(response.url()).pathname === "/items/vanished") statuses.push(response.status())
  })

  await page.goto("/items/vanished")
  await expect(page.locator("#error-status")).toHaveText("Error 404")
  await page.waitForTimeout(500)
  expect(statuses).toEqual([200, 404])
  expect((await page.context().cookies()).map(cookie => cookie.name)).not.toContain(
    "__phoenix_reload_status__"
  )
})

test("a URL no route matches answers the 404 page", async ({ page }) => {
  const response = await page.goto("/no/such/page")
  expect(response.status()).toBe(404)
  await expect(page.locator("#error-status")).toHaveText("Error 404")
})

test("live navigation to an HTTP route loads its response as a page", async ({ page }) => {
  await page.goto("/items")
  await expect(page.locator(".phx-connected")).toBeAttached({ timeout: 15_000 })

  await page.getByRole("link", { name: "Report" }).click()
  await expect(page).toHaveURL(/\/items\/report\.txt$/)
  await expect(page.locator("body")).toHaveText("Item report")
})
