import { defineConfig, devices } from "@playwright/test";

const baseURL =
  process.env.VMS_E2E_FRONTEND_URL ?? "http://127.0.0.1:4176";

export default defineConfig({
  testDir: "./e2e",
  testMatch: /finance-system\.spec\.ts/,
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  timeout: 120_000,
  reporter: [
    ["list"],
    [
      "html",
      {
        open: "never",
        outputFolder: "node_modules/.cache/playwright-system-report",
      },
    ],
  ],
  outputDir: "node_modules/.cache/playwright-system-results",
  use: {
    ...devices["Desktop Chrome"],
    baseURL,
    timezoneId: "Asia/Kolkata",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
  },
  projects: [
    {
      name: "f05-finance-system-chromium",
    },
  ],
});
