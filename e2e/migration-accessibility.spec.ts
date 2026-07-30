import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

import { migrationIds, mockMigrationApi } from "./fixtures/migration-api";
import "./fixtures/quality-gates";

async function assertAccessible(page: Page) {
  const result = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();
  const blocking = result.violations.filter(
    ({ impact }) => impact === "critical" || impact === "serious",
  );
  expect(blocking.map(({ id, impact, help, nodes }) => ({
    id,
    impact,
    help,
    targets: nodes.map(({ target }) => target),
  }))).toEqual([]);
}

test("[E2E-F06-A11Y-001] migration workbench passes WCAG and row filters are keyboard reachable", async ({
  page,
}) => {
  await mockMigrationApi(page);
  await page.goto(`/migration?jobId=${migrationIds.job}`);
  await expect(page.getByRole("heading", {
    name: "Historical migration center",
  })).toBeVisible();
  await expect(page.getByRole("table", {
    name: "Governed migration row page",
  })).toBeVisible();
  await assertAccessible(page);

  const filter = page.getByRole("combobox", { name: "Row state" });
  await filter.focus();
  await expect(filter).toBeFocused();
  await filter.press("v");
  await expect(filter).toHaveValue("VALID");
});

test("[E2E-F06-A11Y-002] retro inbox and readiness controls remain named at tablet width", async ({
  page,
}) => {
  await page.setViewportSize({ width: 1024, height: 768 });
  await mockMigrationApi(page);
  await page.goto(`/migration?jobId=${migrationIds.job}`);
  await expect(page.getByRole("table", {
    name: "Historical request inbox",
  })).toBeVisible();
  await expect(page.getByLabel("Historical month readiness")).toBeVisible();
  await assertAccessible(page);

  const dimensions = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    content: document.documentElement.scrollWidth,
  }));
  expect(dimensions.content).toBeLessThanOrEqual(dimensions.viewport);
});
