import { expect, test } from "@playwright/test";

import { mockUnauthenticatedSession } from "./fixtures/api";
import "./fixtures/quality-gates";

test.beforeEach(async ({ page }) => {
  await mockUnauthenticatedSession(page);
  await page.route("**/test-bff/login?**", (route) =>
    route.fulfill({
      status: 200,
      contentType: "text/html",
      body: "<!doctype html><title>Test BFF handoff</title><main>BFF handoff</main>",
    }),
  );
});

test("[E2E-F01-008] configured login preserves a safe application return path", async ({
  page,
}) => {
  await page.goto("/login?returnTo=%2Frequirements");
  await page.getByRole("button", { name: "Continue with SSO" }).click();

  await expect(page).toHaveURL(
    "http://127.0.0.1:4175/test-bff/login?returnTo=%2Frequirements",
  );
  await expect(page.getByText("BFF handoff")).toBeVisible();
});

test("[E2E-F01-009] configured login replaces an external return target with the app root", async ({
  page,
}) => {
  await page.goto("/login?returnTo=https%3A%2F%2Fattacker.example%2Fsteal");
  await page.getByRole("button", { name: "Continue with SSO" }).click();

  await expect(page).toHaveURL(
    "http://127.0.0.1:4175/test-bff/login?returnTo=%2F",
  );
  expect(new URL(page.url()).origin).toBe("http://127.0.0.1:4175");
});
