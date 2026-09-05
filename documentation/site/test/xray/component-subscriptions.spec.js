import { expect, test } from "./playwright.js"

for (const viewport of [{ width: 1280, height: 800 }, { width: 390, height: 844 }]) {
  test(`component subscriptions stay local at ${viewport.width}px`, async ({ page }) => {
    const frames = []
    page.on("websocket", (socket) => {
      socket.on("framesent", ({ payload }) => frames.push(JSON.parse(payload.toString())))
    })
    await page.setViewportSize(viewport)
    await page.goto("/examples/component-subscriptions")
    await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")

    const first = page.locator('[data-subscription-component="first-ticker"]')
    const second = page.locator('[data-subscription-component="second-ticker"]')
    const count = async (card) => Number(await card.locator("[data-component-ticks]").textContent())
    await expect.poll(() => count(first)).toBeGreaterThan(0)
    const firstCid = await first.getAttribute("data-phx-component")
    const cancel = first.getByRole("button", { name: "Cancel local ticks" })
    await expect(cancel).toHaveAttribute("phx-target", firstCid)
    await cancel.click()
    await expect(first.locator("[data-component-mode]")).toHaveText("Stopped")
    expect(frames.some((frame) => frame[3] === "event" && frame[4].cid === Number(firstCid))).toBe(true)

    const stopped = await count(first)
    const siblingBefore = await count(second)
    await expect.poll(() => count(second)).toBeGreaterThan(siblingBefore)
    expect(await count(first)).toBe(stopped)

    await first.getByRole("button", { name: "Replace local ticks" }).click()
    await expect(first.locator("[data-component-mode]")).toHaveText("Four times per second")
    await expect.poll(() => count(first)).toBeGreaterThan(stopped + 1)

    await page.getByRole("button", { name: "Remove first ticker", exact: true }).click()
    await expect(first).toHaveCount(0)
    await expect.poll(() => frames.some((frame) =>
      frame[3] === "cids_destroyed" && frame[4].cids.includes(Number(firstCid)),
    )).toBe(true)
    await page.getByRole("button", { name: "Reinsert first ticker", exact: true }).click()
    await expect(first).not.toHaveAttribute("data-phx-component", firstCid)
    await expect(first.locator("[data-component-mode]")).toHaveText("Every second")
    await expect.poll(() => count(first)).toBeGreaterThan(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })
}
