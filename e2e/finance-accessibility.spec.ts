import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

import { financeFixture, mockFinanceApi } from "./fixtures/finance-api";
import "./fixtures/quality-gates";

async function assertAccessible(page: Page) {
  const result = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();
  const blocking = result.violations.filter(
    ({ impact }) => impact === "critical" || impact === "serious",
  );
  expect(
    blocking.map(({ id, impact, help, nodes }) => ({
      id,
      impact,
      help,
      targets: nodes.map(({ target }) => target),
    })),
  ).toEqual([]);
}

test("[E2E-F05-A11Y-001] finance workspace has no serious WCAG violations and supports keyboard entry", async ({
  page,
}) => {
  await mockFinanceApi(page);
  await page.goto(
    `/finance?monthId=${financeFixture.ids.month}&invoiceId=${financeFixture.ids.invoice}`,
  );
  await expect(
    page.getByRole("heading", { name: "Finance evidence workspace" }),
  ).toBeVisible({ timeout: 15_000 });
  await assertAccessible(page);

  await page.keyboard.press("Tab");
  await expect(page.locator(":focus")).not.toHaveJSProperty(
    "tagName",
    "BODY",
  );
});

test("[E2E-F05-A11Y-002] Procurement controls meet the automated WCAG gate", async ({
  page,
}) => {
  await mockFinanceApi(page);
  await page.goto(
    `/finance/procurement?invoiceId=${financeFixture.ids.invoice}`,
  );
  await expect(
    page.getByRole("heading", { name: "Review AF-2026-071" }),
  ).toBeVisible({ timeout: 15_000 });
  await assertAccessible(page);
});

test("[E2E-F05-A11Y-003] reports remain accessible without page overflow at tablet width", async ({
  page,
}) => {
  await page.setViewportSize({ width: 1024, height: 768 });
  await mockFinanceApi(page);
  await page.goto("/finance/reports");
  await expect(
    page.getByRole("heading", {
      name: "Finance dashboards, reports and exports",
    }),
  ).toBeVisible({ timeout: 15_000 });
  await assertAccessible(page);

  const dimensions = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    content: document.documentElement.scrollWidth,
  }));
  expect(dimensions.content).toBeLessThanOrEqual(dimensions.viewport);
});
