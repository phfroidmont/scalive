import { expect, test } from "@playwright/test"

test("direct root mounts independently and handles an event", async ({ page }) => {
  const errors = []
  let websocketOpened = false

  page.on("pageerror", error => errors.push(error.message))
  page.on("console", message => {
    if (message.type() === "error") errors.push(message.text())
  })
  page.on("websocket", () => {
    websocketOpened = true
  })

  await page.goto("/")

  const counter = page.getByLabel("Counter value")
  await expect(counter).toHaveText("2", { timeout: 15_000 })
  await page.getByRole("button", { name: "Increment" }).click()
  await expect(counter).toHaveText("3")

  await page.evaluate(() => window.liveSocket.getSocket().sendHeartbeat())
  await expect
    .poll(() => page.evaluate(() => window.liveSocket.getSocket().pendingHeartbeatRef))
    .toBe(null)

  expect(websocketOpened).toBe(true)
  expect(errors).toEqual([])
})

test("nested lifecycles join independently, handle events, and retire as a subtree", async ({ page }) => {
  const errors = []
  const sent = []

  page.on("pageerror", error => errors.push(error.message))
  page.on("console", message => {
    if (message.type() === "error") errors.push(message.text())
  })
  page.on("websocket", socket => {
    socket.on("framesent", event => sent.push(event.payload))
  })

  await page.goto("/nested")

  await expect(page.locator("#nested-child-content")).toBeVisible({ timeout: 15_000 })
  await expect(page.locator("#nested-grandchild-content")).toHaveText("grandchild")
  await expect(page.locator("#nested-child-counter")).toHaveText("0")
  await expect
    .poll(() => sent.some(frame => frame.includes('"lv:nested-child"') && frame.includes('"phx_join"')))
    .toBe(true)
  await expect
    .poll(() => sent.some(frame => frame.includes('"lv:nested-grandchild"') && frame.includes('"phx_join"')))
    .toBe(true)

  await page.getByRole("button", { name: "Increment child" }).click()
  await expect(page.locator("#nested-child-counter")).toHaveText("1")

  await page.getByRole("button", { name: "Toggle child" }).click()
  await expect(page.locator("#nested-child")).toHaveCount(0)
  await expect(page.locator("#nested-grandchild")).toHaveCount(0)
  expect(errors).toEqual([])
})

test("sticky nested lifecycle reattaches across compatible navigation", async ({ page }) => {
  const errors = []
  const sent = []

  page.on("pageerror", error => errors.push(error.message))
  page.on("console", message => {
    if (message.type() === "error") errors.push(message.text())
  })
  page.on("websocket", socket => {
    socket.on("framesent", event => sent.push(event.payload))
  })

  await page.goto("/nested/a")

  await expect(page.locator("#sticky-parent-a")).toBeVisible({ timeout: 15_000 })
  await expect
    .poll(() => sent.filter(frame => frame.includes('"lv:sticky-nested-child"') && frame.includes('"phx_join"')).length)
    .toBe(1)
  await expect
    .poll(() => sent.filter(frame => frame.includes('"lv:sticky-nested-grandchild"') && frame.includes('"phx_join"')).length)
    .toBe(1)
  await page.getByRole("button", { name: "Increment sticky child" }).click()
  await expect(page.locator("#sticky-nested-counter")).toHaveText("1")
  await page.getByRole("button", { name: "Increment sticky grandchild" }).click()
  await expect(page.locator("#sticky-nested-grandchild-counter")).toHaveText("1")

  await page.getByRole("link", { name: "To other sticky page" }).click()

  await expect(page).toHaveURL(/\/nested\/b$/)
  await expect(page.locator("#sticky-parent-b")).toBeVisible()
  await expect(page.locator("#sticky-nested-counter")).toHaveText("1")
  await expect(page.locator("#sticky-nested-grandchild-counter")).toHaveText("1")
  await expect
    .poll(() => sent.filter(frame => frame.includes('"lv:sticky-nested-child"') && frame.includes('"phx_join"')).length)
    .toBe(1)
  await expect
    .poll(() => sent.filter(frame => frame.includes('"lv:sticky-nested-grandchild"') && frame.includes('"phx_join"')).length)
    .toBe(1)
  await page.getByRole("button", { name: "Increment sticky child" }).click()
  await expect(page.locator("#sticky-nested-counter")).toHaveText("2")
  await page.getByRole("button", { name: "Increment sticky grandchild" }).click()
  await expect(page.locator("#sticky-nested-grandchild-counter")).toHaveText("2")
  expect(errors).toEqual([])
})

test("same-session navigation replaces the root over the existing websocket", async ({ page }) => {
  const documents = []
  const frames = []

  page.on("request", request => {
    if (request.resourceType() === "document") documents.push(request.url())
  })
  page.on("websocket", socket => {
    socket.on("framesent", event => frames.push(event.payload))
    socket.on("framereceived", event => frames.push(event.payload))
  })

  await page.goto("/nav/a")
  await expect(page.locator("#view-a")).toBeVisible()
  documents.length = 0
  frames.length = 0

  await page.getByRole("button", { name: "To B" }).click()

  await expect(page).toHaveURL(/\/nav\/b$/)
  await expect(page.locator("#view-b")).toBeVisible()
  await expect(page.locator("#flash")).toContainText("Flash from A")
  await expect(page.locator("body#root-one")).toBeVisible()
  expect(documents).toEqual([])
  expect(frames.some(frame => frame.includes("live_redirect"))).toBe(true)
  expect(frames.some(frame => frame.includes("phx_leave"))).toBe(true)
  expect(frames.some(frame => frame.includes("phx_join"))).toBe(true)
})

test("incompatible navigation falls back to HTTP and preserves flash", async ({ page }) => {
  const documents = []

  page.on("request", request => {
    if (request.resourceType() === "document") documents.push(request.url())
  })

  await page.goto("/nav/a")
  await expect(page.locator("#view-a")).toBeVisible()
  documents.length = 0

  await page.getByRole("button", { name: "To C" }).click()

  await expect(page).toHaveURL(/\/nav\/c$/)
  await expect(page.locator("#view-c")).toBeVisible()
  await expect(page.locator("#flash")).toContainText("Flash from A")
  await expect(page.locator("body#root-two")).toBeVisible()
  expect(documents.some(url => url.endsWith("/nav/c"))).toBe(true)

  documents.length = 0
  await page.getByRole("button", { name: "To A" }).click()

  await expect(page).toHaveURL(/\/nav\/a$/)
  await expect(page.locator("#view-a")).toBeVisible()
  await expect(page.locator("#flash")).toContainText("Flash from C")
  await expect(page.locator("body#root-one")).toBeVisible()
  expect(documents.some(url => url.endsWith("/nav/a"))).toBe(true)
})

test("root layout mismatch falls back to a fresh document", async ({ page }) => {
  const documents = []

  page.on("request", request => {
    if (request.resourceType() === "document") documents.push(request.url())
  })

  await page.goto("/nav/a")
  documents.length = 0
  await page.getByRole("button", { name: "To D" }).click()

  await expect(page).toHaveURL(/\/nav\/d$/)
  await expect(page.locator("#view-d")).toBeVisible()
  await expect(page.locator("#flash")).toContainText("Flash from A")
  await expect(page.locator("body#root-two")).toBeVisible()
  expect(documents.some(url => url.endsWith("/nav/d"))).toBe(true)
})

test("full redirect performs HTTP navigation and transfers flash", async ({ page }) => {
  const documents = []

  page.on("request", request => {
    if (request.resourceType() === "document") documents.push(request.url())
  })

  await page.goto("/nav/a")
  await page.getByRole("button", { name: "To B" }).click()
  await expect(page.locator("#view-b")).toBeVisible()
  documents.length = 0

  await page.getByRole("button", { name: "Redirect to C" }).click()

  await expect(page).toHaveURL(/\/nav\/c$/)
  await expect(page.locator("#view-c")).toBeVisible()
  await expect(page.locator("#flash")).toContainText("Flash from B")
  expect(documents.some(url => url.endsWith("/nav/c"))).toBe(true)
})

test("hosted upload transfers and consumes file bytes", async ({ page }) => {
  const errors = []

  page.on("pageerror", error => errors.push(error.message))
  page.on("console", message => {
    if (message.type() === "error") errors.push(message.text())
  })

  await page.goto("/upload")
  await expect(page.locator("#upload-connected")).toHaveText("true", { timeout: 15_000 })
  await page.locator('input[type="file"]').setInputFiles({
    name: "proof.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("hosted payload"),
  })
  await expect(page.locator(".upload-name")).toHaveText("proof.txt")

  await page.getByRole("button", { name: "Upload" }).click()

  await expect(page.locator("#uploaded-name")).toHaveText("proof.txt")
  await expect(page.locator("#uploaded-content")).toHaveText("hosted payload")
  await expect(page.locator(".upload-entry")).toHaveCount(0)
  expect(errors).toEqual([])
})

test("hosted upload rejects an unacceptable file without consuming it", async ({ page }) => {
  const errors = []

  page.on("pageerror", error => errors.push(error.message))
  page.on("console", message => {
    if (message.type() === "error") errors.push(message.text())
  })

  await page.goto("/upload")
  await expect(page.locator("#upload-connected")).toHaveText("true", { timeout: 15_000 })
  await page.locator('input[type="file"]').setInputFiles({
    name: "blocked.exe",
    mimeType: "application/octet-stream",
    buffer: Buffer.from("blocked"),
  })

  await expect(page.locator(".upload-error")).toContainText("Unacceptable file type")
  await page.getByRole("button", { name: "Upload" }).click()
  await expect(page.locator(".uploaded-file")).toHaveCount(0)
  expect(errors).toEqual([])
})
