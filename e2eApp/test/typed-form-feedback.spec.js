import { expect, test } from "@playwright/test"

let unexpectedErrors

test.beforeEach(async ({ page }) => {
  unexpectedErrors = []
  page.on("pageerror", error => unexpectedErrors.push(error.message))
  page.on("console", message => {
    if (message.type() === "error") unexpectedErrors.push(message.text())
  })

  await page.goto("/form/typed-feedback")
  await expect(page.locator(".phx-connected")).toBeAttached({ timeout: 15_000 })
})

test.afterEach(() => {
  expect(unexpectedErrors).toEqual([])
})

const input = (page, field) => page.locator(`#typed-feedback [name="typed[${field}]"]`)
const error = (page, field) => page.locator(`#typed-feedback [phx-feedback-for="typed[${field}]"]`)

test("unchanged blur preserves status and reveals only its field", async ({ page }) => {
  await input(page, "name").focus()
  await page.locator("#outside").click()

  await expect(error(page, "name")).toHaveText("Name is required")
  await expect(error(page, "email")).toHaveText("")
  await expect(page.locator("#status")).toHaveText("Preserved")
  await expect(page.locator("#blur-count")).toHaveText("1")
})

test("a field validates live only after its first blur", async ({ page }) => {
  const name = input(page, "name")
  await name.fill("draft")
  await expect(page.locator("#server-name")).toHaveText("draft")
  await name.fill("")
  await expect(page.locator("#server-name")).toHaveText("")
  await expect(error(page, "name")).toHaveText("")
  await page.locator("#outside").click()
  await expect(error(page, "name")).toHaveText("Name is required")
  await name.fill("fixed")
  await expect(page.locator("#server-name")).toHaveText("fixed")
  await expect(error(page, "name")).toHaveText("")
})

test("numeric debounce applies the latest typed value after its deadline", async ({ page }) => {
  const email = input(page, "email")
  await email.pressSequentially("latest@example.test")
  await expect(page.locator("#server-email")).toHaveText("latest@example.test")
})

test("scalar onBlur follows feedback once and normalizes its forwarded value", async ({ page }) => {
  const phone = input(page, "phone")
  await phone.fill(" 123 ")
  await expect.poll(() => page.locator("#server-phone").textContent()).toBe(" 123 ")
  await page.locator("#outside").click()

  await expect(phone).toHaveValue("123")
  await expect(page.locator("#blur-count")).toHaveText("1")
  await expect(page.locator("#phone-effect-count")).toHaveText("1")
  await expect(page.locator("#effects")).toContainText("update:blurred|phone: 123 ")
})

test("checkbox and multiple select serialize their complete values", async ({ page }) => {
  await input(page, "checkbox").check()
  await input(page, "select").selectOption(["red", "blue"])

  await expect(page.locator("#server-checkbox")).toHaveText("checked")
  await expect(page.locator("#server-select")).toHaveText("red,blue")
  await input(page, "checkbox").uncheck()
  await expect(page.locator("#server-checkbox")).toHaveText("")
})

test("an unchecked checkbox forwards one empty scalar blur callback", async ({ page }) => {
  await input(page, "checkbox").focus()
  await page.locator("#outside").click()

  await expect(page.locator("#server-checkbox")).toHaveText("")
  await expect(page.locator("#checkbox-blur-value")).toHaveText("")
  await expect(page.locator("#checkbox-blur-count")).toHaveText("1")
  await expect(page.locator("#effects")).toHaveText("update:blurred|checkbox:")
})

test("textarea validation starts after blur and follows later edits", async ({ page }) => {
  const notes = input(page, "notes")
  const notesErrorId = await error(page, "notes").getAttribute("id")
  await expect(notes).toHaveAttribute("aria-describedby", notesErrorId)
  await expect(notes).not.toHaveAttribute("aria-invalid")
  await expect(error(page, "notes")).toHaveText("")

  await notes.focus()
  await page.locator("#outside").click()
  await expect(error(page, "notes")).toHaveText("Notes are required")
  await expect(notes).toHaveAttribute("aria-invalid", "true")

  await notes.fill("edited in a textarea")
  await expect(page.locator("#server-notes")).toHaveText("edited in a textarea")
  await expect(error(page, "notes")).toHaveText("")
  await expect(notes).not.toHaveAttribute("aria-invalid")
})

test("component form binding routes updates and scalar blur to its component", async ({ page }) => {
  const componentName = page.locator('#component-feedback [name="component[name]"]')
  await componentName.fill("component value")
  await expect(page.locator("#component-server-name")).toHaveText("component value")
  await page.locator("#outside").click()

  await expect(page.locator("#component-blur-value")).toHaveText("component value")
  await expect(page.locator("#component-blur-count")).toHaveText("1")
  await componentName.fill("")
  await expect(page.locator('#component-feedback [phx-feedback-for="component[name]"]')).toHaveText(
    "Component name is required",
  )
  await expect(page.locator("#server-name")).toHaveText("")
})

test("disconnect remounts, recovers values, and starts fresh feedback history", async ({ page }) => {
  const name = input(page, "name")
  const componentName = page.locator('#component-feedback [name="component[name]"]')
  const initialMountId = Number(await page.locator("#mount-id").textContent())

  await name.fill("recover main")
  await page.locator("#outside").click()
  await componentName.fill("recover component")
  await expect(page.locator("#blur-count")).toHaveText("1")

  await page.evaluate(() => window.liveSocket.disconnect())
  await expect(page.locator(".phx-connected")).toHaveCount(0)
  await page.evaluate(() => window.liveSocket.connect())
  await expect(page.locator(".phx-connected")).toBeAttached({ timeout: 15_000 })

  await expect.poll(async () => Number(await page.locator("#mount-id").textContent())).toBeGreaterThan(
    initialMountId,
  )
  await expect(page.locator("#server-name")).toHaveText("recover main")
  await expect(page.locator("#component-server-name")).toHaveText("recover component")
  await expect(page.locator("#effects")).toHaveText("update:recovered")
  await expect(page.locator("#update-count")).toHaveText("1")
  await expect(page.locator("#blur-count")).toHaveText("0")

  await name.fill("")
  await expect(page.locator("#server-name")).toHaveText("")
  await expect(error(page, "name")).toHaveText("")
  await expect(page.locator("#effects")).toHaveText("update:recovered|update:changed")
})

test("submit feedback stays visible until reset", async ({ page }) => {
  await page.locator("#submit").click()
  await expect(page.locator("#submit-count")).toHaveText("1")
  await expect(error(page, "name")).toHaveText("Name is required")
  await expect(error(page, "email")).toHaveText("Email is required")
  await input(page, "name").fill("fixed")
  await expect(error(page, "name")).toHaveText("")
  await expect(error(page, "email")).toHaveText("Email is required")

  await page.locator("#reset").click()
  await expect(page.locator("#submit-count")).toHaveText("0")
  await expect(page.locator("#status")).toHaveText("Preserved")
  await expect(error(page, "email")).toHaveText("")
})

test("semantic custom-control blur applies to the latest mapped choice", async ({ page }) => {
  await page.locator("#choice-option").focus()
  await page.locator("#choice-option").click()
  await page.locator("#outside").focus()

  await expect(page.locator("#server-choice")).toHaveText("selected")
  await expect(page.locator("#blur-count")).toHaveText("1")
  await expect(error(page, "choice")).toHaveText("")
})

test("keyed row errors move with a key and replacement starts fresh", async ({ page }) => {
  const rowA = page.locator('[data-row-key="row-a"]')
  const rowAInput = rowA.locator("input[type=text]")
  await rowAInput.focus()
  await page.locator("#outside").click()
  await expect(rowA.locator(".form-errors")).toHaveText("Row name is required")

  await rowA.locator('[data-move-row="row-a"]').click()
  await expect(page.locator("[data-row-key]").last()).toHaveAttribute("data-row-key", "row-a")
  await expect(page.locator('[data-row-key="row-a"] .form-errors')).toHaveText("Row name is required")

  await page.locator('[data-row-key="row-a"] [data-remove-row="row-a"]').click()
  await expect(page.locator('[data-row-key="row-a"]')).toHaveCount(0)
  await page.locator("#add-replacement").click()
  await expect(page.locator('[data-row-key="row-a"] .form-errors')).toHaveText("")
  await expect(page.locator('[data-row-key="row-a"] input[type=hidden]')).toHaveCount(1)
})
