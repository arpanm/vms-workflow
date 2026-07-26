import { defineConfig, devices } from "@playwright/test";

const sharedEnvironment = {
  VITE_API_BASE_URL: "/api/v1",
  VITE_FEATURE_LEGACY_FIXED_COST: "true",
  VITE_FEATURE_WORKFORCE_GOVERNANCE: "false",
  VITE_FEATURE_GREYTHR: "false",
  VITE_FEATURE_LINEAR: "false",
  VITE_FEATURE_EMAIL_REPLY_INGESTION: "false",
  VITE_BACKEND_DEV_URL: "http://127.0.0.1:9",
};

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: [
    ["list"],
    [
      "html",
      {
        open: "never",
        outputFolder: "node_modules/.cache/playwright-report",
      },
    ],
  ],
  outputDir: "node_modules/.cache/playwright-results",
  expect: {
    timeout: 5_000,
  },
  use: {
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
  },
  webServer: [
    {
      command: "npm run dev -- --host 127.0.0.1 --port 4173 --strictPort",
      url: "http://127.0.0.1:4173",
      env: {
        ...sharedEnvironment,
        VITE_DEMO_MODE: "true",
        VITE_FEATURE_WORKFORCE_GOVERNANCE: "true",
        VITE_FEATURE_LINEAR: "true",
      },
      reuseExistingServer: false,
      timeout: 120_000,
    },
    {
      command: "npm run dev -- --host 127.0.0.1 --port 4174 --strictPort",
      url: "http://127.0.0.1:4174",
      env: {
        ...sharedEnvironment,
        VITE_DEMO_MODE: "false",
      },
      reuseExistingServer: false,
      timeout: 120_000,
    },
    {
      command: "npm run dev -- --host 127.0.0.1 --port 4175 --strictPort",
      url: "http://127.0.0.1:4175",
      env: {
        ...sharedEnvironment,
        VITE_DEMO_MODE: "false",
        VITE_OIDC_LOGIN_PATH: "/test-bff/login",
      },
      reuseExistingServer: false,
      timeout: 120_000,
    },
  ],
  projects: [
    {
      name: "demo-chromium",
      testMatch: /demo\.spec\.ts/,
      use: {
        ...devices["Desktop Chrome"],
        baseURL: "http://127.0.0.1:4173",
      },
    },
    {
      name: "auth-no-bff-chromium",
      testMatch: /auth-disabled\.spec\.ts/,
      use: {
        ...devices["Desktop Chrome"],
        baseURL: "http://127.0.0.1:4174",
      },
    },
    {
      name: "auth-bff-chromium",
      testMatch: /redirect-safety\.spec\.ts/,
      use: {
        ...devices["Desktop Chrome"],
        baseURL: "http://127.0.0.1:4175",
      },
    },
    {
      name: "workforce-chromium",
      testMatch: /workforce\.spec\.ts/,
      use: {
        ...devices["Desktop Chrome"],
        baseURL: "http://127.0.0.1:4173",
      },
    },
    {
      name: "delivery-chromium",
      testMatch: /delivery\.spec\.ts/,
      use: {
        ...devices["Desktop Chrome"],
        baseURL: "http://127.0.0.1:4173",
      },
    },
  ],
});
