import { expect, test } from "@playwright/test"

let unexpectedErrors

test.beforeEach(async ({ page }) => {
  unexpectedErrors = []
  page.on("pageerror", error => unexpectedErrors.push(error.message))
  page.on("console", message => {
    if (message.type() === "error") unexpectedErrors.push(message.text())
  })

  await page.goto("/form/blur-feedback")
  await expect(page.locator(".phx-connected")).toBeAttached({ timeout: 15_000 })
})

test.afterEach(() => {
  expect(unexpectedErrors).toEqual([])
})

const field = (page, name) => page.locator(`#${name}`)
const error = (page, name) => page.locator(`#${name}-error`)
const serverValue = (page, name) => page.locator(`#server-${name}`)
const events = page => page.locator("#events li")

test("an untouched blur marks only its target required and preserves status", async ({ page }) => {
  await field(page, "plain").focus()
  await page.locator("#outside").click()

  await expect(error(page, "plain")).toHaveText("Required")
  for (const name of ["timed", "throttled", "blur-only", "phone", "other"]) {
    await expect(error(page, name)).toHaveText("")
  }
  await expect(page.locator("#blur-count")).toHaveText("1")
  await expect(page.locator("#change-count")).toHaveText("0")
  await expect(page.locator("#status")).toHaveText("Preserved")
  await expect(events(page).last()).toHaveAttribute("data-blur-target", "plain")
})

test("ordinary fields reveal feedback on blur and correct it live afterward", async ({ page }) => {
  const plain = field(page, "plain")

  await plain.fill("draft")
  await expect(serverValue(page, "plain")).toHaveText("draft")
  await plain.fill("")
  await expect(serverValue(page, "plain")).toHaveText("")
  await expect(error(page, "plain")).toHaveText("")

  await page.locator("#outside").click()
  await expect(error(page, "plain")).toHaveText("Required")

  await plain.fill("fixed")
  await expect(serverValue(page, "plain")).toHaveText("fixed")
  await expect(error(page, "plain")).toHaveText("")
})

test("debounce sends the latest numeric snapshot and clears the blur marker for the next event", async ({ page }) => {
  const timed = field(page, "timed")

  await timed.pressSequentially("123")
  const debounce = Number(await timed.getAttribute("phx-debounce"))
  // Check within the debounce window, not before a round trip could have completed.
  await page.waitForTimeout(debounce / 2)
  await expect(page.locator("#change-count")).toHaveText("0")
  await expect(serverValue(page, "timed")).toHaveText("123")
  await expect(page.locator("#change-count")).toHaveText("1")
  await expect(events(page).last()).toHaveAttribute("data-blur-target", "")
  await expect(events(page).last()).toHaveAttribute("data-phone", "")
  await expect(events(page).last()).toContainText("timed=123")

  await page.locator("#outside").click()
  await expect(page.locator("#blur-count")).toHaveText("1")
  await expect(events(page).last()).toHaveAttribute("data-blur-target", "timed")

  await field(page, "plain").fill("7")
  await expect(serverValue(page, "plain")).toHaveText("7")
  await expect(events(page).last()).toHaveAttribute("data-blur-target", "")
  await expect(events(page).last()).toContainText("plain=7")
})

test("throttle delivers the final latest value when the field blurs", async ({ page }) => {
  const throttled = field(page, "throttled")

  await throttled.pressSequentially("123")
  await throttled.blur()

  await expect(serverValue(page, "throttled")).toHaveText("123")
  await expect(page.locator('#events li[data-blur-target="throttled"]').last()).toContainText("throttled=123")
})

test("blur-only fields deliver unchanged and repeatedly edited values on blur", async ({ page }) => {
  const blurOnly = field(page, "blur-only")

  await blurOnly.focus()
  await page.locator("#outside").click()
  await expect(page.locator("#blur-count")).toHaveText("1")
  await expect(events(page).last()).toHaveAttribute("data-blur-target", "blur-only")
  await expect(serverValue(page, "blur-only")).toHaveText("")

  await blurOnly.fill("a")
  await blurOnly.fill("again")
  // A component event is a server round-trip barrier without serializing the main form.
  await page.locator("#choice-search").evaluate(input => {
    input.value = "barrier"
    input.dispatchEvent(new Event("input", { bubbles: true }))
  })
  await expect(page.locator("#server-query")).toHaveText("barrier")
  await expect(serverValue(page, "blur-only")).toHaveText("")
  // This characterizes blur-only delivery, not whether its feedback should update live.
  await page.locator("#outside").click()
  await expect(serverValue(page, "blur-only")).toHaveText("again")
  await expect(page.locator("#blur-count")).toHaveText("2")
  await expect(events(page).last()).toHaveAttribute("data-blur-target", "blur-only")
  await expect(events(page).last()).toContainText("blur-only=again")
})

test("rapid tabbing keeps each blur marker on the field that lost focus", async ({ page }) => {
  await field(page, "plain").focus()
  await page.keyboard.press("Tab")
  await page.keyboard.press("Tab")

  await expect(page.locator("#blur-count")).toHaveText("2")
  const blurTargets = await events(page).evaluateAll(items =>
    items.map(item => item.getAttribute("data-blur-target")).filter(Boolean),
  )
  expect(blurTargets).toEqual(["plain", "timed"])
  await expect(error(page, "plain")).toHaveText("Required")
  await expect(error(page, "timed")).toHaveText("Required")
  await expect(error(page, "throttled")).toHaveText("")
})

test("submit shows sticky errors and reset clears the form state", async ({ page }) => {
  await page.getByRole("button", { name: "Submit" }).click()

  await expect(page.locator("#submit-count")).toHaveText("1")
  for (const name of ["plain", "timed", "throttled", "blur-only", "phone", "other"]) {
    await expect(error(page, name)).toHaveText("Required")
  }

  await field(page, "plain").fill("fixed")
  await expect(error(page, "plain")).toHaveText("")
  await expect(error(page, "other")).toHaveText("Required")

  await page.locator("#reset").click()
  await expect(page.locator("#submit-count")).toHaveText("0")
  await expect(page.locator("#blur-count")).toHaveText("0")
  await expect(page.locator("#change-count")).toHaveText("0")
  await expect(page.locator("#status")).toHaveText("Preserved")
  await expect(events(page)).toHaveCount(0)
  for (const name of ["plain", "timed", "throttled", "blur-only", "phone", "other"]) {
    await expect(field(page, name)).toHaveValue("")
    await expect(error(page, name)).toHaveText("")
  }
})

test("phone normalization uses one blur marker and survives a later acknowledged event", async ({ page }) => {
  const phone = field(page, "phone")

  await phone.fill(" 123 ")
  await expect.poll(() => serverValue(page, "phone").evaluate(element => element.textContent)).toBe(" 123 ")
  await field(page, "other").focus()

  await expect(phone).toHaveValue("123")
  await expect(page.locator("#blur-count")).toHaveText("1")
  const phoneBlurEvents = page.locator('#events li[data-blur-target="phone"]')
  await expect(phoneBlurEvents).toHaveCount(1)
  await expect(phoneBlurEvents).toHaveAttribute("data-phone", " 123 ")

  await field(page, "other").fill("next")
  await expect(serverValue(page, "other")).toHaveText("next")
  await expect(phone).toHaveValue("123")
  await expect(events(page).last()).toHaveAttribute("data-blur-target", "")
  await expect(events(page).last()).toHaveAttribute("data-phone", "123")
})

test("characterizes the known same-task race that overwrites normalized phone with raw input", async ({ page }) => {
  // This intentionally asserts the limitation described in README.md, not desired API behavior.
  await page.evaluate(() => {
    const phone = document.querySelector("#phone")
    const other = document.querySelector("#other")

    phone.focus()
    phone.value = " 555 "
    phone.dispatchEvent(new Event("input", { bubbles: true }))
    phone.blur()
    other.focus()
    other.value = "raced"
    other.dispatchEvent(new Event("input", { bubbles: true }))
  })

  await expect(serverValue(page, "other")).toHaveText("raced")
  await expect.poll(() => serverValue(page, "phone").evaluate(element => element.textContent)).toBe(" 555 ")

  const phoneBlur = page.locator('#events li[data-blur-target="phone"]')
  await expect(phoneBlur).toHaveCount(1)
  await expect(phoneBlur).toHaveAttribute("data-phone", " 555 ")
  await expect(phoneBlur).toHaveAttribute("data-phone-after", "555")
  const subsequentChange = page.locator('#events li[data-blur-target=""][data-other="raced"]')
  await expect(subsequentChange).toHaveCount(1)
  await expect(subsequentChange).toHaveAttribute("data-phone", " 555 ")
})

test("numeric debounce flushes before its deadline and does not replay the blur marker", async ({ page }) => {
  const debounce = Number(await field(page, "timed").getAttribute("phx-debounce"))
  await page.evaluate(() => {
    const input = document.querySelector("#timed")
    input.focus()
    input.value = "latest"
    input.dispatchEvent(new Event("input", { bubbles: true }))
    input.blur()
  })

  await expect(serverValue(page, "timed")).toHaveText("latest")
  await expect(page.locator("#blur-count")).toHaveText("1")
  await expect(page.locator("#change-count")).toHaveText("1")
  expect(await events(page).evaluateAll(items => items.map(item => item.dataset.blurTarget))).toEqual(["", "timed"])
  // Wait beyond the original debounce deadline to detect a stale callback.
  await page.waitForTimeout(debounce + 100)
  await expect(events(page)).toHaveCount(2)
})

test("composite search and internal focus do not reveal feedback until the widget is left", async ({ page }) => {
  await page.locator("#choice-search").fill("query")
  await page.locator("#choice-option").focus()
  // Queue a component round trip after the internal focus movement.
  await page.locator("#choice-search").evaluate(input => {
    input.value = "barrier"
    input.dispatchEvent(new Event("input", { bubbles: true }))
  })
  await expect(page.locator("#server-query")).toHaveText("barrier")
  await expect(page.locator("#blur-count")).toHaveText("0")
  await expect(page.locator("#choice-error")).toHaveText("")
  await expect(page.locator("#change-count")).toHaveText("0")

  await page.locator("#outside").focus()
  await expect(page.locator("#blur-count")).toHaveText("1")
  await expect(page.locator("#choice-error")).toHaveText("Required")
  await expect(page.locator("#status")).toHaveText("Preserved")
  await expect(events(page).last()).toHaveAttribute("data-blur-target", "choice")
})

test("blur snapshots include another control's pending value without revealing its errors", async ({ page }) => {
  await page.evaluate(() => {
    const timed = document.querySelector("#timed")
    // Model a custom control emitting an edit without taking focus.
    timed.value = "pending"
    timed.dispatchEvent(new Event("input", { bubbles: true }))
    const plain = document.querySelector("#plain")
    plain.focus()
    plain.blur()
  })

  await expect(serverValue(page, "timed")).toHaveText("pending")
  await expect(error(page, "plain")).toHaveText("Required")
  await expect(error(page, "timed")).toHaveText("")
  await expect(page.locator("#blur-count")).toHaveText("1")
  await expect(page.locator('#events li[data-blur-target="plain"]')).toContainText("timed=pending")
  await expect(page.locator("#change-count")).toHaveText("1")
  await expect(events(page).last()).toHaveAttribute("data-blur-target", "")
  // Make the unblurred field invalid to prove its feedback is still hidden.
  await field(page, "timed").evaluate(input => {
    input.value = ""
    input.dispatchEvent(new Event("input", { bubbles: true }))
  })
  await expect(page.locator("#change-count")).toHaveText("2")
  await expect(serverValue(page, "timed")).toHaveText("")
  await expect(error(page, "timed")).toHaveText("")
})

test("composite null-target blur reveals errors and a later selection clears them", async ({ page }) => {
  await page.locator("#choice-search").focus()
  await page.locator("#choice-search").blur()
  await expect(page.locator("#choice-error")).toHaveText("Required")
  await page.locator("#choice-option").click()
  await expect(page.locator("#server-choice")).toHaveText("selected")
  await expect(page.locator("#choice-error")).toHaveText("")
})

test("submission while a debounced input is focused keeps the latest values and all feedback", async ({ page }) => {
  await field(page, "timed").fill("pending")
  await page.getByRole("button", { name: "Submit" }).click()
  await expect(page.locator("#submit-count")).toHaveText("1")
  await expect(serverValue(page, "timed")).toHaveText("pending")
  await expect(error(page, "other")).toHaveText("Required")
  await field(page, "plain").fill("changed")
  await expect(serverValue(page, "plain")).toHaveText("changed")
  await expect(error(page, "other")).toHaveText("Required")
})

test("composite selection followed immediately by departure preserves the selected value", async ({ page }) => {
  await page.locator("#choice-search").focus()
  await page.evaluate(() => {
    const option = document.querySelector("#choice-option")
    option.focus()
    option.click()
    document.querySelector("#outside").focus()
  })

  await expect(page.locator("#server-choice")).toHaveText("selected")
  await expect(page.locator("#blur-count")).toHaveText("1")
  await expect(page.locator("#choice-error")).toHaveText("")
  await expect(events(page).last()).toContainText("choice=selected")
  await field(page, "plain").fill("next")
  await expect(serverValue(page, "plain")).toHaveText("next")
  await expect(page.locator("#server-choice")).toHaveText("selected")
})
