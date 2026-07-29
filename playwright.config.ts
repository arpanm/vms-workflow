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
  // Keep the full regression deterministic on developer machines as well as
  // CI. Several suites share the three bounded Vite servers; allowing
  // Playwright to expand to every local CPU can starve route transitions and
  // turn otherwise healthy journeys into timeout-only flakes.
  workers: 2,
  reporter: [
    ["list"],
    [
      "html",
      {
        open: "never",
        outputFolder: "node_modules/.cache/playwright-report",
      },
    ],
    [
      "json",
      {
        outputFile: "node_modules/.cache/playwright-results/results.json",
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
      name: "auth-no-bff-firefox",
      testMatch: /auth-disabled\.spec\.ts/,
      use: {
        ...devices["Desktop Firefox"],
        baseURL: "http://127.0.0.1:4174",
      },
    },
    {
      name: "auth-bff-firefox",
      testMatch: /redirect-safety\.spec\.ts/,
      use: {
        ...devices["Desktop Firefox"],
        baseURL: "http://127.0.0.1:4175",
      },
    },
    {
      name: "auth-no-bff-webkit",
      testMatch: /auth-disabled\.spec\.ts/,
      use: {
        ...devices["Desktop Safari"],
        baseURL: "http://127.0.0.1:4174",
      },
    },
    {
      name: "auth-bff-webkit",
      testMatch: /redirect-safety\.spec\.ts/,
      use: {
        ...devices["Desktop Safari"],
        baseURL: "http://127.0.0.1:4175",
      },
    },
    {
      name: "f01-core-admin-chromium",
      testMatch: /core-admin\.spec\.ts/,
      use: {
        ...devices["Desktop Chrome"],
        baseURL: "http://127.0.0.1:4173",
        timezoneId: "Asia/Kolkata",
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
    {
      name: "f04-certification-chromium",
      testMatch: /certification\.spec\.ts/,
      use: {
        ...devices["Desktop Chrome"],
        baseURL: "http://127.0.0.1:4173",
        timezoneId: "America/New_York",
      },
    },
    {
      name: "f05-finance-chromium",
      testMatch: /finance(?:-accessibility)?\.spec\.ts/,
      use: {
        ...devices["Desktop Chrome"],
        baseURL: "http://127.0.0.1:4173",
        timezoneId: "Asia/Kolkata",
      },
    },
    {
      name: "f06-migration-chromium",
      testMatch: /migration\.spec\.ts/,
      use: {
        ...devices["Desktop Chrome"],
        baseURL: "http://127.0.0.1:4173",
        timezoneId: "Asia/Kolkata",
      },
    },
    {
      name: "f07-accessibility-chromium",
      testMatch: /f07-accessibility\.spec\.ts/,
      use: {
        ...devices["Desktop Chrome"],
        baseURL: "http://127.0.0.1:4173",
        timezoneId: "Asia/Kolkata",
      },
    },
    {
      name: "f07-accessibility-chromium-utc",
      testMatch: /f07-accessibility\.spec\.ts/,
      use: {
        ...devices["Desktop Chrome"],
        baseURL: "http://127.0.0.1:4173",
        timezoneId: "UTC",
      },
    },
    {
      name: "f07-compatibility-firefox",
      fullyParallel: false,
      timeout: 60_000,
      testMatch: [
        /f07-accessibility\.spec\.ts/,
        /workforce\.spec\.ts/,
        /delivery\.spec\.ts/,
        /certification\.spec\.ts/,
        /finance\.spec\.ts/,
        /migration\.spec\.ts/,
      ],
      use: {
        ...devices["Desktop Firefox"],
        baseURL: "http://127.0.0.1:4173",
        timezoneId: "Asia/Kolkata",
      },
    },
    {
      name: "f07-compatibility-webkit",
      fullyParallel: false,
      testMatch: [
        /f07-accessibility\.spec\.ts/,
        /workforce\.spec\.ts/,
        /delivery\.spec\.ts/,
        /certification\.spec\.ts/,
        /finance\.spec\.ts/,
        /migration\.spec\.ts/,
      ],
      use: {
        ...devices["Desktop Safari"],
        baseURL: "http://127.0.0.1:4173",
        timezoneId: "Asia/Kolkata",
      },
    },
    {
      name: "f07-compatibility-android",
      testMatch: /f07-accessibility\.spec\.ts/,
      use: {
        ...devices["Pixel 7"],
        baseURL: "http://127.0.0.1:4173",
        timezoneId: "Asia/Kolkata",
      },
    },
    {
      name: "f07-compatibility-ios",
      fullyParallel: false,
      testMatch: /f07-accessibility\.spec\.ts/,
      use: {
        ...devices["iPhone 13"],
        baseURL: "http://127.0.0.1:4173",
        timezoneId: "Asia/Kolkata",
      },
    },
  ],
});
