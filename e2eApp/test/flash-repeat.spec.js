import { expect, test } from "@playwright/test"

let pageErrors

const mounts = (page, name) => page.evaluate(name => window[name] ?? 0, name)

test.beforeEach(async ({ page }) => {
  pageErrors = []
  page.on("pageerror", error => pageErrors.push(error.message))

  await page.goto("/flash/repeat")
  await expect(page.locator(".phx-connected")).toBeAttached({ timeout: 15_000 })
})

test.afterEach(() => {
  expect(pageErrors).toEqual([])
})

test("putting the same flash again mounts a hook keyed by the put number again", async ({ page }) => {
  await page.locator("#save").click()
  await expect(page.locator("#saved-0")).toHaveText("Saved")
  await expect.poll(() => mounts(page, "flashMounts")).toBe(1)

  await page.locator("#save").click()
  await expect(page.locator("#saved-1")).toHaveText("Saved")
  await expect(page.locator("#saved-0")).toHaveCount(0)
  await expect.poll(() => mounts(page, "flashMounts")).toBe(2)

  // An element whose id does not change is patched in place: its hook mounts once.
  expect(await mounts(page, "fixedFlashMounts")).toBe(1)
})
