import { expect, test } from "@playwright/test"

const SVG = "http://www.w3.org/2000/svg"

test("inline SVG stays SVG when patched and when rows are added", async ({ page }) => {
  await page.goto("/svg")
  await expect(page.locator(".phx-connected")).toBeAttached({ timeout: 15_000 })

  await page.getByRole("button", { name: "Grow" }).click()
  await expect(page.locator("#bar-0")).toHaveAttribute("width", "20")
  await page.getByRole("button", { name: "Add" }).click()
  await expect(page.locator("#bar-1")).toHaveAttribute("width", "5")

  const namespaces = await page.evaluate(() =>
    ["chart", "bar-0", "bar-1"].map(id => document.getElementById(id).namespaceURI)
  )
  expect(namespaces).toEqual([SVG, SVG, SVG])
  const size = await page.locator("#bar-1").evaluate(rect => rect.getBBox().width)
  expect(size).toBe(5)
})
