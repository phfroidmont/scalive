import { expect, test } from "@playwright/test"

let pageErrors

test.beforeEach(async ({ page }) => {
  pageErrors = []
  page.on("pageerror", error => pageErrors.push(error.message))

  await page.goto("/key-derived-rows")
  await expect(page.locator(".phx-connected")).toBeAttached({ timeout: 15_000 })
})

test.afterEach(() => {
  expect(pageErrors).toEqual([])
})

const rows = page =>
  page.locator("#rows > li").evaluateAll(items =>
    items.map(item => ({ id: item.id, key: item.dataset.key, text: item.textContent }))
  )

const row = (key, label = "ready", state = "todo") => ({
  id: `row-${key}`,
  key,
  text: `Row ${key}: ${label}${state}`
})

// The rows share one template on the wire and send what their key changes among their dynamics.
test("rows whose statics derive from their key join, insert and update", async ({ page }) => {
  expect(await rows(page)).toEqual([row("a"), row("b"), row("c")])

  await page.locator("#add").click()
  await expect(page.locator("#rows > li")).toHaveCount(6)
  expect(await rows(page)).toEqual(["a", "b", "c", "d", "e", "f"].map(key => row(key)))

  await page.locator("#rename").click()
  await expect(page.locator("#row-b .label")).toHaveText("renamed")

  await page.locator("#finish").click()
  await expect(page.locator("#done-e")).toHaveText("done")
  await expect(page.locator("#todo-e")).toHaveCount(0)
  expect(await rows(page)).toEqual([
    row("a"),
    row("b", "renamed"),
    row("c"),
    row("d"),
    row("e", "ready", "done"),
    row("f")
  ])
})
