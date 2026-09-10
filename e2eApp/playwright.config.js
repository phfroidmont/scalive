import path from "node:path";
import { fileURLToPath } from "node:url";

const appRoot = path.dirname(fileURLToPath(import.meta.url));

export default {
  testDir: path.join(appRoot, "test"),
  forbidOnly: !!process.env.CI,
  reporter: process.env.CI ? [["github"], ["dot"]] : [["list"]],
  timeout: 30_000,
  use: {
    baseURL: "http://localhost:4006/",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  webServer: {
    command: "SCALIVE_SERVER_PORT=4006 mill -i e2eApp.run",
    cwd: path.dirname(appRoot),
    url: "http://localhost:4006/health",
    reuseExistingServer: false,
    timeout: 120_000,
    stdout: "pipe",
    stderr: "pipe",
  },
  projects: [
    { name: "chromium", use: { channel: "chromium" } },
    {
      name: "firefox",
      testMatch: "**/typed-form-feedback.spec.js",
      use: { browserName: "firefox" },
    },
    {
      name: "webkit",
      testMatch: "**/typed-form-feedback.spec.js",
      use: { browserName: "webkit" },
    },
  ],
};
