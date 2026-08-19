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
