import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

import { mockCertificationApi } from "./fixtures/certification-api";
import { certificationFixture } from "./fixtures/certification-api";
import { deliveryFixture, mockDeliveryApi } from "./fixtures/delivery-api";
import { mockFinanceApi } from "./fixtures/finance-api";
import { financeFixture } from "./fixtures/finance-api";
import { mockLegacyApi } from "./fixtures/api";
import { migrationIds, mockMigrationApi } from "./fixtures/migration-api";
import { mockWorkforceApi } from "./fixtures/workforce-api";
import "./fixtures/quality-gates";

async function expectNoSeriousViolations(page: Page) {
  const result = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();
  const blocking = result.violations.filter((violation) =>
    ["serious", "critical"].includes(violation.impact ?? ""),
  );
  expect(
    blocking,
    blocking
      .map(
        (violation) =>
          `${violation.id}: ${violation.help} (${violation.nodes
            .map((node) => node.target.join(" "))
            .join(", ")})`,
      )
      .join("\n"),
  ).toEqual([]);
}

async function enableAccessibleDisplayPreferences(page: Page) {
  // A 1280px display at 200% browser zoom exposes roughly a 640 CSS-pixel
  // layout viewport. Shrinking the layout viewport exercises genuine reflow;
  // CSS `zoom` would incorrectly double fixed-width chrome and manufacture
  // overflow that browser zoom does not.
  await page.setViewportSize({ width: 640, height: 450 });
  await page.emulateMedia({
    colorScheme: "dark",
    forcedColors: "active",
    reducedMotion: "reduce",
  });
}

async function expectNoHorizontalActionLoss(page: Page) {
  const layout = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
    focusedVisible:
      document.activeElement instanceof HTMLElement &&
      document.activeElement.getBoundingClientRect().width > 0 &&
      document.activeElement.getBoundingClientRect().height > 0,
  }));
  expect(layout.scrollWidth).toBeLessThanOrEqual(layout.clientWidth + 2);
  expect(layout.focusedVisible).toBe(true);
}

test("[F07-A11Y-001A] shell, skip link and safe not-found recovery pass the automated gate", async ({
  page,
  browserName,
}) => {
  await mockLegacyApi(page);
  await mockFinanceApi(page);
  await page.goto("/");
  const skipLink = page.getByRole("link", { name: "Skip to main content" });
  await expect(skipLink).toBeAttached();
  if (browserName === "webkit") {
    // Playwright WebKit follows macOS/iOS Full Keyboard Access defaults and
    // does not synthesize link focus from Tab reliably in headless mode.
    await skipLink.focus();
  } else {
    await page.locator("body").evaluate((body) => {
      body.setAttribute("tabindex", "-1");
      body.focus();
      body.removeAttribute("tabindex");
    });
    await page.keyboard.press("Tab");
  }
  await expect(skipLink).toBeFocused();
  await skipLink.press("Enter");
  await expect(page.locator("#main-content")).toBeFocused();
  await expectNoSeriousViolations(page);

  await page.goto("/not-a-real-feature");
  await expect(page.getByRole("heading", { name: "404" })).toBeVisible();
  await expectNoSeriousViolations(page);
});

test("[F07-A11Y-001B] employee mobile attendance remains labelled and reflows", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockWorkforceApi(page);
  await page.goto("/attendance/today");
  await expect(page.getByRole("heading", { name: "Today" })).toBeVisible();
  await expect(page.locator("body")).toHaveJSProperty("scrollWidth", 390);
  await expectNoSeriousViolations(page);
});

test("[F07-A11Y-001C] certification governance route has no serious or critical violation", async ({
  page,
}) => {
  await mockCertificationApi(page);
  await page.goto("/certification");
  await expect(page.getByRole("heading", { name: "Delivery certification" })).toBeVisible();
  await expectNoSeriousViolations(page);
});

test("[F07-A11Y-001D] finance workspace remains keyboard-named at tablet width", async ({
  page,
}) => {
  await page.setViewportSize({ width: 768, height: 1024 });
  await mockFinanceApi(page);
  await page.goto("/finance");
  await expect(page.getByRole("heading", { name: "Finance evidence workspace" })).toBeVisible();
  await expectNoSeriousViolations(page);
});

test("[F07-A11Y-001E] migration center exposes named controls without blocking violations", async ({
  page,
}) => {
  await mockMigrationApi(page);
  await page.goto("/migration");
  await expect(page.getByRole("heading", { name: /Migration Center/i })).toBeVisible();
  await expectNoSeriousViolations(page);
});

test("[F07-A11Y-002A] reduced-motion mode preserves an operable navigation path", async ({
  page,
}) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await mockLegacyApi(page);
  await mockFinanceApi(page);
  await page.goto("/");
  const requirements = page.getByRole("link", { name: "Requirements" });
  if ((await requirements.count()) === 0) {
    await page.getByRole("button", { name: "Toggle Sidebar" }).click();
  }
  await requirements.focus();
  await expect(requirements).toBeFocused();
  if ((page.viewportSize()?.width ?? 1024) < 600) {
    await requirements.click();
  } else {
    await requirements.press("Enter");
  }
  await expect(page.getByRole("heading", { name: "Legacy Requirements" })).toBeVisible();
});

test("[F07-A11Y-002B] 200% zoom and forced colors preserve the attendance mutation", async ({
  page,
}) => {
  const api = await mockWorkforceApi(page, {
    attendanceScenario: "OPEN_SESSION",
  });
  await page.goto("/attendance/today");
  await enableAccessibleDisplayPreferences(page);
  const action = page.getByRole("button", { name: "Check out" });
  await action.focus();
  await expect(action).toBeFocused();
  await action.press("Enter");
  await expect
    .poll(() =>
      api.mutations.some(
        (mutation) => mutation.path === "/api/v1/attendance/punches",
      ),
    )
    .toBe(true);
  await expectNoHorizontalActionLoss(page);
});

test("[F07-A11Y-002C] plan validation is keyboard-triggered, linked and visible at 200% zoom", async ({
  page,
}) => {
  await mockDeliveryApi(page);
  await page.goto("/delivery/plans/new");
  await enableAccessibleDisplayPreferences(page);
  const action = page.getByRole("button", { name: "Create draft" });
  await action.focus();
  await action.press("Enter");
  const errors = page.getByLabel("Plan validation errors");
  await expect(errors).toContainText("Plan title is required.");
  await expect(page.getByText(deliveryFixture.ids.month, { exact: true }))
    .toHaveCount(0);
  await errors.focus();
  await expectNoHorizontalActionLoss(page);
});

test("[F07-A11Y-002D] confirmation error recovery returns focus without a trap at 200% zoom", async ({
  page,
}) => {
  await mockCertificationApi(page);
  await page.goto(
    `/confirmation/requests/${certificationFixture.ids.request}`,
  );
  await enableAccessibleDisplayPreferences(page);
  await page.getByLabel("Request correction").check();
  const action = page.getByRole("button", {
    name: "Record action for exact version",
  });
  await action.focus();
  await action.press("Enter");
  const errors = page.getByRole("alert", {
    name: /confirmation action errors/i,
  });
  await expect(errors).toContainText(
    "Reason and required correction is required.",
  );
  await expect(errors).toBeFocused();
  await expectNoHorizontalActionLoss(page);
});

test("[F07-A11Y-002E] invoice evidence controls retain names and keyboard reach at 200% zoom", async ({
  page,
}) => {
  await mockFinanceApi(page, { withoutDocument: true });
  await page.goto(
    `/finance?monthId=${financeFixture.ids.month}&invoiceId=${financeFixture.ids.invoice}`,
  );
  await enableAccessibleDisplayPreferences(page);
  const file = page.getByLabel("Invoice file");
  await file.focus();
  await expect(file).toBeFocused();
  await file.setInputFiles({
    name: "नियंत्रित-चालान.pdf",
    mimeType: "application/pdf",
    buffer: Buffer.from("%PDF-1.7 governed UTF-8 evidence"),
  });
  await expect(file).toHaveValue(/नियंत्रित-चालान\.pdf$/);
  await page.getByLabel("Reason", { exact: true })
    .fill("सत्यापित प्रतिस्थापन स्रोत");
  const upload = page.getByRole("button", { name: "Upload for scan" });
  await upload.focus();
  await expect(upload).toBeFocused();
  await expectNoHorizontalActionLoss(page);
});

test("[F07-A11Y-002F] migration validation remains operable with zoom, forced colors and reduced motion", async ({
  page,
}) => {
  await mockMigrationApi(page);
  await page.goto(`/migration?jobId=${migrationIds.job}`);
  await enableAccessibleDisplayPreferences(page);
  const validate = page.getByRole("button", {
    name: "Validate staged rows",
  });
  await validate.focus();
  await validate.press("Enter");
  await expect(page.getByText("MIG-REF-EMPLOYEE-NOT-FOUND")).toBeVisible();
  await validate.focus();
  await expectNoHorizontalActionLoss(page);
});

test("[F07-A11Y-003] browser matrix preserves timezone boundaries, constrained storage and UTF-8 filenames", async ({
  page,
}) => {
  await page.addInitScript(() => {
    Object.defineProperty(window, "localStorage", {
      configurable: true,
      get() {
        throw new DOMException("Storage disabled by policy", "SecurityError");
      },
    });
    Object.defineProperty(window, "sessionStorage", {
      configurable: true,
      get() {
        throw new DOMException("Storage disabled by policy", "SecurityError");
      },
    });
  });
  await mockWorkforceApi(page);
  await page.goto("/attendance/today");
  await expect(page.getByRole("heading", { name: "Today" })).toBeVisible();

  const boundary = await page.evaluate(() => {
    const value = new Date("2026-07-31T20:30:00Z");
    const formatter = new Intl.DateTimeFormat("en-GB", {
      day: "2-digit",
      month: "2-digit",
      year: "numeric",
    });
    return {
      timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      localDate: formatter.format(value),
    };
  });
  if (boundary.timezone === "Asia/Calcutta"
      || boundary.timezone === "Asia/Kolkata") {
    expect(boundary.localDate).toBe("01/08/2026");
  } else {
    expect(boundary.timezone).toBe("UTC");
    expect(boundary.localDate).toBe("31/07/2026");
  }

  await page.unrouteAll({ behavior: "wait" });
  const migration = await mockMigrationApi(page);
  await page.goto(`/migration?jobId=${migrationIds.job}`);
  await page.getByRole("combobox", { name: /^Template/ })
    .selectOption("01_employees");
  await page.getByLabel("CSV source file").setInputFiles({
    name: "कर्मचारी-जुलाई.csv",
    mimeType: "text/csv",
    buffer: Buffer.from(
      "template_version,organization_code,employee_name\n1,ARROW,अनन्या राव\n",
    ),
  });
  await page.getByLabel("Organization ID").fill(migrationIds.organization);
  await page.getByLabel("Source description")
    .fill("जुलाई का सत्यापित कर्मचारी अभिलेख");
  await page.getByRole("button", { name: "Upload for dry run" }).click();
  await expect
    .poll(() =>
      migration.requests.some(
        (request) =>
          request.method === "POST" &&
          request.path === "/api/v1/migrations/jobs",
      ),
    )
    .toBe(true);
  await expect.poll(() => migration.multipartFileNames())
    .toContain("कर्मचारी-जुलाई.csv");
});
