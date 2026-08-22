import path from "node:path"
import { fileURLToPath } from "node:url"

const siteRoot = path.dirname(fileURLToPath(import.meta.url))
const repositoryRoot = path.resolve(siteRoot, "../..")

export default {
  testDir: path.join(siteRoot, "test/xray"),
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["github"], ["dot"]] : [["list"]],
  timeout: 30_000,
  use: {
    baseURL: "http://127.0.0.1:4005",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  webServer: {
    command:
      "SCALIVE_SERVER_PORT=4005 SCALIVE_PUBLIC_ORIGIN=http://127.0.0.1:4005 mill --no-daemon --ticker false documentation.site.run",
    cwd: repositoryRoot,
    url: "http://127.0.0.1:4005/examples",
    reuseExistingServer: !process.env.CI,
    stdout: "pipe",
    stderr: "pipe",
  },
  projects: [
    {
      name: "chromium",
      use: { browserName: "chromium" },
    },
  ],
}
