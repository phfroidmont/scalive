import { expect, test } from "@playwright/test"

let pageErrors
let dialogs

test.beforeEach(async ({ page }) => {
  pageErrors = []
  dialogs = []
  page.on("pageerror", error => pageErrors.push(error.message))
})

test.afterEach(() => {
  expect(pageErrors).toEqual([])
})

const openDirty = async (page, path) => {
  await page.goto(path)
  await expect(page.locator(".phx-connected")).toBeAttached({ timeout: 15_000 })
  await page.locator("#edit").click()
  await expect(page.locator("#dirty")).toHaveText("true")
}

// Accepts or dismisses every dialog, recording its message.
const answerDialogs = (page, accept) =>
  page.on("dialog", dialog => {
    dialogs.push(dialog.message())
    return accept ? dialog.accept() : dialog.dismiss()
  })

test("the default scope lets live patches pass and guards leaving the view", async ({ page }) => {
  answerDialogs(page, false)
  await openDirty(page, "/navigation-guard/leaving")

  await page.getByRole("link", { name: "Tab two" }).click()
  await expect(page.locator("#tab")).toHaveText("two")
  await page.goBack()
  await expect(page.locator("#tab")).toHaveText("one")
  expect(dialogs).toEqual([])
  await expect(page.locator("#dirty")).toHaveText("true")

  await page.getByRole("link", { name: "Leave" }).click()
  await expect.poll(() => dialogs).toEqual(["Leave without saving?"])
  await expect(page).toHaveURL(/\/navigation-guard\/leaving/)
})

test("the any-navigation scope also guards live patches and history across them", async ({ page }) => {
  answerDialogs(page, false)
  await openDirty(page, "/navigation-guard/any")

  await page.getByRole("link", { name: "Tab two" }).click()
  await expect.poll(() => dialogs).toEqual(["Leave without saving?"])
  await expect(page.locator("#tab")).toHaveText("one")

  page.removeAllListeners("dialog")
  answerDialogs(page, true)
  await page.getByRole("link", { name: "Tab two" }).click()
  await expect(page.locator("#tab")).toHaveText("two")

  page.removeAllListeners("dialog")
  answerDialogs(page, false)
  await page.goBack()
  await expect.poll(() => dialogs.length).toBe(3)
  await expect(page.locator("#tab")).toHaveText("two")
})
