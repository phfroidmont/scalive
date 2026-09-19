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
const topicValues = page =>
  page.locator("#typed-feedback").evaluate(form => new FormData(form).getAll("typed[topics]"))

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
  await expect(phone).toHaveAttribute("type", "tel")
  await phone.fill(" 123 ")
  await expect.poll(() => page.locator("#server-phone").textContent()).toBe(" 123 ")
  await page.locator("#outside").click()

  await expect(phone).toHaveValue("123")
  await expect(page.locator("#blur-count")).toHaveText("1")
  await expect(page.locator("#phone-effect-count")).toHaveText("1")
  await expect(page.locator("#effects")).toContainText("update:blurred|phone: 123 ")
})

test("native date sanitization reaches the server through the next full-form snapshot", async ({ page }) => {
  const date = input(page, "date")
  const name = input(page, "name")
  const serverDateRaw = page.locator("#server-date-raw")

  await expect(date).toHaveAttribute("type", "date")
  await expect(date).toHaveValue("")
  await expect(serverDateRaw).toHaveText('[""]')
  await expect(date).not.toBeFocused()

  await page.locator("#seed-invalid-date").click()
  await expect(serverDateRaw).toHaveText('["not-a-date"]')
  await expect(date).toHaveAttribute("value", "not-a-date")
  await expect(date).toHaveValue("")

  await name.fill("snapshot trigger")
  await expect(serverDateRaw).toHaveText('[""]')
  await expect(date).toHaveAttribute("value", "")
  await expect(date).toHaveValue("")

  await date.fill("2026-09-18")
  await expect(date).toHaveValue("2026-09-18")
  await expect(serverDateRaw).toHaveText('["2026-09-18"]')
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

test("checkbox item labels activate distinct controls and preserve repeated values", async ({ page }) => {
  const scala = page.getByLabel("Scala", { exact: true })
  const elixir = page.getByLabel("Elixir", { exact: true })
  const scalaLabel = page.locator("label").filter({ hasText: /^Scala$/ })
  const elixirLabel = page.locator("label").filter({ hasText: /^Elixir$/ })
  const topics = input(page, "topics")
  const serverTopics = page.locator("#server-topics")

  await expect(topics).toHaveCount(3)
  const ids = await topics.evaluateAll(inputs => inputs.map(input => input.id))
  expect(ids.every(Boolean)).toBe(true)
  expect(new Set(ids).size).toBe(3)
  await expect(scalaLabel).toHaveAttribute("for", await scala.getAttribute("id"))
  await expect(elixirLabel).toHaveAttribute("for", await elixir.getAttribute("id"))
  await expect(serverTopics).toHaveText("[]")

  await elixirLabel.click()
  await expect(elixir).toBeChecked()
  await expect(scala).not.toBeChecked()
  await expect(serverTopics).toHaveText('["elixir"]')
  expect(await topicValues(page)).toEqual(["elixir"])

  await scalaLabel.click()
  await expect(scala).toBeChecked()
  await expect(elixir).toBeChecked()
  await expect(serverTopics).toHaveText('["scala","elixir"]')
  expect(await topicValues(page)).toEqual(["scala", "elixir"])

  await elixirLabel.click()
  await expect(elixir).not.toBeChecked()
  await expect(scala).toBeChecked()
  await expect(serverTopics).toHaveText('["scala"]')
  expect(await topicValues(page)).toEqual(["scala"])

  await scalaLabel.click()
  await expect(scala).not.toBeChecked()
  await expect(serverTopics).toHaveText("[]")
  expect(await topicValues(page)).toEqual([])
})

test("checkbox items patch server selections and omit disabled values on submit", async ({ page }) => {
  const scala = page.getByLabel("Scala", { exact: true })
  const elixir = page.getByLabel("Elixir", { exact: true })
  const erlang = page.getByLabel("Erlang", { exact: true })
  const serverTopics = page.locator("#server-topics")

  await scala.check()
  await expect(serverTopics).toHaveText('["scala"]')
  await expect(elixir).not.toBeChecked()
  await expect(erlang).not.toBeChecked()
  await expect(erlang).toBeDisabled()

  await page.locator("#seed-topics").click()
  await expect(serverTopics).toHaveText('["elixir","erlang"]')
  await expect(scala).not.toBeChecked()
  await expect(elixir).toBeChecked()
  await expect(erlang).toBeChecked()
  expect(await topicValues(page)).toEqual(["elixir"])

  await page.locator("#submit").click()
  await expect(page.locator("#submit-count")).toHaveText("1")
  await expect(serverTopics).toHaveText('["elixir"]')
  await expect(elixir).toBeChecked()
  await expect(erlang).not.toBeChecked()
})

test("a reactive checkbox token changes submission without changing identity or retained values", async ({ page }) => {
  const elixir = page.getByLabel("Elixir", { exact: true })
  const serverTopics = page.locator("#server-topics")
  const originalId = await elixir.getAttribute("id")

  await elixir.check()
  await expect(serverTopics).toHaveText('["elixir"]')
  await page.locator("#change-topic-token").click()
  await expect(elixir).toHaveValue("beam")
  await expect(elixir).toHaveAttribute("id", originalId)
  await expect(elixir).not.toBeChecked()
  await expect(serverTopics).toHaveText('["elixir"]')
  expect(await topicValues(page)).toEqual([])

  await elixir.check()
  await expect(serverTopics).toHaveText('["beam"]')
  expect(await topicValues(page)).toEqual(["beam"])
})

test("checkbox items share feedback and blur callbacks even when focus stays in the group", async ({ page }) => {
  const scala = page.getByLabel("Scala", { exact: true })
  const elixir = page.getByLabel("Elixir", { exact: true })
  const topicsError = error(page, "topics")

  await expect(topicsError).toHaveCount(1)
  await expect(topicsError).toHaveText("")
  const errorId = await topicsError.getAttribute("id")
  for (const topic of await input(page, "topics").all()) {
    await expect(topic).toHaveAttribute("aria-describedby", errorId)
    await expect(topic).not.toHaveAttribute("aria-invalid")
  }

  await scala.focus()
  await elixir.focus()
  await expect(elixir).toBeFocused()
  await expect(topicsError).toHaveText("Choose at least one topic")
  await expect(error(page, "name")).toHaveText("")
  await expect(page.locator("#blur-count")).toHaveText("1")
  await expect(page.locator("#topics-blur-count")).toHaveText("1")
  await expect(page.locator("#topics-blur-value")).toHaveText("")
  await expect(page.locator("#effects")).toHaveText("update:blurred|topics:")
  for (const topic of await input(page, "topics").all()) {
    await expect(topic).toHaveAttribute("aria-invalid", "true")
  }

  await elixir.check()
  await expect(page.locator("#server-topics")).toHaveText('["elixir"]')
  await expect(topicsError).toHaveText("")
  for (const topic of await input(page, "topics").all()) {
    await expect(topic).not.toHaveAttribute("aria-invalid")
  }
  await scala.focus()
  await expect(scala).toBeFocused()
  await expect(page.locator("#blur-count")).toHaveText("2")
  await expect(page.locator("#topics-blur-count")).toHaveText("2")
  await expect(page.locator("#topics-blur-value")).toHaveText("elixir")
  await expect(page.locator("#effects")).toHaveText(
    "update:blurred|topics:|update:changed|update:blurred|topics:elixir",
  )
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
  await page.getByLabel("Scala", { exact: true }).check()
  await page.getByLabel("Elixir", { exact: true }).check()
  await page.locator("#outside").click()
  await expect(page.locator("#server-topics")).toHaveText('["scala","elixir"]')
  await componentName.fill("recover component")
  await expect(page.locator("#blur-count")).toHaveText("3")

  await page.evaluate(() => window.liveSocket.disconnect())
  await expect(page.locator(".phx-connected")).toHaveCount(0)
  await page.evaluate(() => window.liveSocket.connect())
  await expect(page.locator(".phx-connected")).toBeAttached({ timeout: 15_000 })

  await expect.poll(async () => Number(await page.locator("#mount-id").textContent())).toBeGreaterThan(
    initialMountId,
  )
  await expect(page.locator("#server-name")).toHaveText("recover main")
  await expect(page.locator("#server-topics")).toHaveText('["scala","elixir"]')
  await expect(page.getByLabel("Scala", { exact: true })).toBeChecked()
  await expect(page.getByLabel("Elixir", { exact: true })).toBeChecked()
  await expect(page.getByLabel("Erlang", { exact: true })).not.toBeChecked()
  expect(await topicValues(page)).toEqual(["scala", "elixir"])
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
  await expect(error(page, "topics")).toHaveText("Choose at least one topic")
  await page.getByLabel("Scala", { exact: true }).check()
  await page.getByLabel("Elixir", { exact: true }).check()
  await expect(page.locator("#server-topics")).toHaveText('["scala","elixir"]')
  await expect(error(page, "topics")).toHaveText("")
  await input(page, "name").fill("fixed")
  await expect(error(page, "name")).toHaveText("")
  await expect(error(page, "email")).toHaveText("Email is required")

  await page.locator("#reset").click()
  await expect(page.locator("#submit-count")).toHaveText("0")
  await expect(page.locator("#status")).toHaveText("Preserved")
  await expect(error(page, "email")).toHaveText("")
  await expect(page.locator("#server-topics")).toHaveText("[]")
  await expect(error(page, "topics")).toHaveText("")
  for (const topic of await input(page, "topics").all()) {
    await expect(topic).not.toBeChecked()
    await expect(topic).not.toHaveAttribute("aria-invalid")
  }
  expect(await topicValues(page)).toEqual([])
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
