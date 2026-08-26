import { expect, test } from "./playwright.js"

const loginPath = "/examples/authentication/lab"
const profilePath = `${loginPath}/profile`

test("revokes one application session across tabs and denies reconnect", async ({ page, context }) => {
  await page.goto(loginPath)
  await page.getByLabel("Email").fill("alice@example.com")
  await page.getByLabel("Password").fill("scalive")
  await page.getByRole("button", { name: "Sign in" }).click()

  await expect(page).toHaveURL(new RegExp(`${profilePath}$`))
  await expect(page.locator("html")).toHaveAttribute("data-connection-state", "connected")
  await expect(page.getByRole("heading", { name: "Welcome, Alice" })).toBeVisible()

  const sessionCookie = (await context.cookies()).find(
    (cookie) => cookie.name === "__scalive_docs_auth_lab",
  )
  expect(sessionCookie).toMatchObject({ httpOnly: true, sameSite: "Lax" })

  const secondTab = await context.newPage()
  await secondTab.goto(profilePath)
  await expect(secondTab.locator("html")).toHaveAttribute("data-connection-state", "connected")
  await expect(secondTab.getByRole("heading", { name: "Welcome, Alice" })).toBeVisible()

  await page.getByRole("button", { name: "Sign out and reset lab" }).click()

  await expect(page).toHaveURL(new RegExp(`${loginPath}$`))
  await expect(secondTab).toHaveURL(new RegExp(`${loginPath}$`))

  const newTab = await context.newPage()
  await newTab.goto(profilePath)
  await expect(newTab).toHaveURL(new RegExp(`${loginPath}$`))
})
