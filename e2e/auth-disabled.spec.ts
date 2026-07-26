import { expect, test } from "@playwright/test";

import { mockLegacyApi, mockUnauthenticatedSession } from "./fixtures/api";
import "./fixtures/quality-gates";

test.beforeEach(async ({ page }) => {
  // The router can begin loading a deep-linked screen before its auth redirect
  // commits, so keep those speculative reads deterministic too.
  await mockLegacyApi(page);
  await mockUnauthenticatedSession(page);
});

test("[E2E-F01-006] login is visibly disabled when no same-origin BFF is configured", async ({
  page,
}) => {
  await page.goto("/login");

  await expect(page.getByText("Sign in to Cadence")).toBeVisible();
  await expect(page.getByText("Sign-in is blocked")).toBeVisible();
  await expect(
    page.getByRole("button", { name: "SSO configuration required" }),
  ).toBeDisabled();
});

test("[E2E-F01-007] an unauthenticated same-origin deep link returns to the login gate", async ({
  page,
}) => {
  await page.goto("/requirements?filter=pending");

  await expect(page).toHaveURL(
    /\/login\?returnTo=%2Frequirements$/,
  );
  await expect(
    page.getByRole("button", { name: "SSO configuration required" }),
  ).toBeDisabled();
});
