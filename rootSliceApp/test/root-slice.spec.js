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
