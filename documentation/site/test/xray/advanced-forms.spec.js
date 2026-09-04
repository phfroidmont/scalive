import { expect, test } from "./playwright.js"

test("keeps repeated contact identities stable while rows change", async ({ page }) => {
  await page.goto("/examples/repeated-contacts-form")
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")

  const example = page.locator('[data-example="repeated-contacts-form"]')
  const form = example.locator("[data-contacts-form]")

  await expect(form.locator("[data-contact-key]")).toHaveCount(2)
  await form.getByRole("button", { name: "Add contact" }).click()
  const added = form.locator('[data-contact-key="contact-3"]')
  await expect(added).toBeVisible()
  const addedPayload = await form.evaluate((element) =>
    Array.from(new FormData(element), ([name, value]) => [name, value]),
  )
  expect(addedPayload).toContainEqual([
    "contact_book[contacts][contact-3][_scalive_row]",
    "1",
  ])
  await form.getByRole("button", { name: "Save contacts" }).click()
  await expect(added.locator(".form-error")).toHaveCount(2)
  await added.getByLabel("Name").fill("Katherine Johnson")
  await added.getByLabel("Email").fill("katherine@example.com")
  await added.getByRole("button", { name: "Move contact-3 up" }).click()

  await expect.poll(() => example.locator("[data-summary-key]").evaluateAll((rows) =>
    rows.map((row) => row.getAttribute("data-summary-key")),
  )).toEqual(["contact-1", "contact-3", "contact-2"])
  await expect(form.locator('[data-contact-key="contact-3"]').getByLabel("Name"))
    .toHaveValue("Katherine Johnson")
  await expect(form.locator('[data-contact-key="contact-2"]').getByLabel("Name"))
    .toHaveValue("Grace Hopper")

  await form.getByRole("button", { name: "Move contact-1 down" }).click()
  await expect.poll(() => example.locator("[data-summary-key]").evaluateAll((rows) =>
    rows.map((row) => row.getAttribute("data-summary-key")),
  )).toEqual(["contact-3", "contact-1", "contact-2"])
  await expect(form.locator('[data-contact-key="contact-3"]').getByLabel("Email"))
    .toHaveValue("katherine@example.com")
  await expect(form.locator('[data-contact-key="contact-1"]').getByLabel("Name"))
    .toHaveValue("Ada Lovelace")

  await form.getByRole("button", { name: "Remove contact-1" }).click()
  await expect(form.locator('[data-contact-key="contact-1"]')).toHaveCount(0)
  await expect(form.locator('[data-contact-key="contact-3"]').getByLabel("Name"))
    .toHaveValue("Katherine Johnson")
  await form.getByRole("button", { name: "Save contacts" }).click()
  await expect(example.locator("[data-contacts-saved]")).toHaveText(
    "Saved 2 contacts in the displayed order.",
  )
})

test("preserves newer edits across a correlated form save", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto("/examples/form-save-workflow")
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")

  const example = page.locator('[data-example="form-save-workflow"]')
  const form = example.locator("[data-workflow-form]")
  const title = form.getByLabel("Draft title")

  await title.fill("Release notes")
  await form.getByRole("button", { name: "Begin save" }).click()
  await title.fill("Newer local edit")
  await example.getByRole("button", { name: "Reset to baseline" }).click()
  await expect(example.locator("[data-workflow-notice]")).toContainText("blocked")

  await example.getByRole("button", { name: "Simulate success" }).click()
  await expect(title).toHaveValue("Newer local edit")
  await expect(example.locator("[data-workflow-dirty]")).toHaveText("true")
  await expect(example.locator("[data-workflow-baseline-advancements]")).toHaveText("1")

  await example.getByRole("button", { name: "Replay stale success" }).click()
  await expect(example.locator("[data-workflow-notice]")).toContainText("stale completion")
  await example.getByRole("button", { name: "Reset to baseline" }).click()
  await expect(title).toHaveValue("Release notes")
})
