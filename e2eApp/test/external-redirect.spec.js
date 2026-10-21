import { expect, test } from "@playwright/test"

test("a message handler redirects the browser to another origin", async ({ page }) => {
  await page.route("https://checkout.example.test/**", route =>
    route.fulfill({ contentType: "text/html", body: "<h1>Checkout</h1>" })
  )
  await page.goto("/external-redirect")
  await expect(page.locator(".phx-connected")).toBeAttached({ timeout: 15_000 })

  await page.getByRole("button", { name: "Checkout" }).click()
  await expect(page).toHaveURL("https://checkout.example.test/pay?session=a1")
  await expect(page.locator("h1")).toHaveText("Checkout")
})
