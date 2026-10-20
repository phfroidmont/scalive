import { expect, test } from "@playwright/test"

let pageErrors

// The marker survives live navigation and reconnects, but not a page load.
const loadMarker = page => page.evaluate(() => window.loadMarker === true)

test.beforeEach(async ({ page }) => {
  pageErrors = []
  page.on("pageerror", error => pageErrors.push(error.message))

  await page.goto("/form-unsaved/target")
  await expect(page.locator(".phx-connected")).toBeAttached({ timeout: 15_000 })
  await page.evaluate(() => {
    window.loadMarker = true
    window.liveSocket.js().navigate("/form/typed-feedback")
  })
  await expect(page.locator("#typed-feedback")).toBeAttached()
  await expect(page.locator(".phx-connected")).toBeAttached({ timeout: 15_000 })
  expect(await loadMarker(page)).toBe(true)
})

test.afterEach(() => {
  expect(pageErrors).toEqual([])
})

test("a live-navigated LiveView rejoins after a reconnect and recovers its draft", async ({ page }) => {
  const name = page.locator('#typed-feedback [name="typed[name]"]')
  const initialMountId = Number(await page.locator("#mount-id").textContent())

  await name.fill("draft")
  await expect(page.locator("#server-name")).toHaveText("draft")

  await page.evaluate(() => {
    window.liveSocket.disconnect()
    setTimeout(() => window.liveSocket.connect(), 200)
  })
  await expect.poll(async () => Number(await page.locator("#mount-id").textContent())).toBeGreaterThan(
    initialMountId,
  )
  await expect(page.locator(".phx-connected")).toBeAttached({ timeout: 15_000 })

  await expect(page.locator("#server-name")).toHaveText("draft")
  await expect(name).toHaveValue("draft")
  await expect(page.locator("#effects")).toHaveText("update:recovered")
  expect(await loadMarker(page)).toBe(true)
})

test("a reload from a live-navigated LiveView loads it again", async ({ page }) => {
  await page.reload()

  await expect(page).toHaveURL(/\/form\/typed-feedback$/)
  await expect(page.locator(".phx-connected")).toBeAttached({ timeout: 15_000 })
  expect(await loadMarker(page)).toBe(false)
})

test("a page request from a live-navigated LiveView reaches its destination", async ({ page }) => {
  await page.evaluate(() => window.location.assign("/form-unsaved"))

  await expect(page).toHaveURL(/\/form-unsaved$/)
  await expect(page.locator("#unsaved-form")).toBeAttached()
  await expect(page.locator(".phx-connected")).toBeAttached({ timeout: 15_000 })
  expect(await loadMarker(page)).toBe(false)
})
