import { expect, test } from "@playwright/test";

import { migrationIds, mockMigrationApi } from "./fixtures/migration-api";

test("[E2E-F06-PKG-001] imported consumed package routes through governed reopen and superseding version flow", async ({
  page,
}) => {
  await mockMigrationApi(page, { consumedPackage: true });
  await page.goto(`/migration?jobId=${migrationIds.job}`);

  const correction = page.getByText(
    "Consumed evidence requires governed correction.",
  );
  await expect(correction).toBeVisible();
  await expect(page.getByText(
    /new superseding F05 package version/i,
  )).toBeVisible();
  await expect(page.getByRole("link", {
    name: "Open governed month correction",
  })).toHaveAttribute(
    "href",
    `/certification/${migrationIds.month}`,
  );
});
