import path from "node:path";

const repoRoot = process.env.SCALIVE_REPO_ROOT;
const upstreamRoot = process.env.LV_LOCAL_E2E_DIR;

if (!repoRoot) {
  throw new Error("SCALIVE_REPO_ROOT is not set");
}

if (!upstreamRoot) {
  throw new Error("LV_LOCAL_E2E_DIR is not set");
}

export default {
  testDir: path.join(upstreamRoot, "test/e2e/tests"),
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? [["github"], ["html"], ["dot"]] : [["list"]],
  timeout: 30_000,
  use: {
    baseURL: "http://localhost:4004/",
    trace: "retain-on-failure",
    screenshot: "only-on-failure"
  },
  webServer: {
    command: "SCALIVE_SERVER_PORT=4004 mill -i e2eApp.run",
    cwd: repoRoot,
    url: "http://localhost:4004/health",
    reuseExistingServer: !process.env.CI,
    stdout: "pipe",
    stderr: "pipe"
  },
  projects: [
    {
      name: "chromium",
      use: {
        channel: "chromium"
      }
    }
  ]
};
