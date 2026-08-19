import path from "node:path"
import { fileURLToPath } from "node:url"

const appRoot = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.dirname(appRoot)

export default {
  testDir: path.join(appRoot, "test"),
  forbidOnly: !!process.env.CI,
  reporter: process.env.CI ? [["github"], ["dot"]] : [["list"]],
  timeout: 30_000,
  use: {
    baseURL: "http://localhost:4005/",
    trace: "retain-on-failure",
    screenshot: "only-on-failure"
  },
  webServer: {
    command: "SCALIVE_SERVER_PORT=4005 mill -i rootSliceApp.run",
    cwd: repoRoot,
    url: "http://localhost:4005/health",
    reuseExistingServer: false,
    stdout: "pipe",
    stderr: "pipe"
  },
  projects: [
    {
      name: "chromium",
      use: { channel: "chromium" }
    }
  ]
}
