import { expect, test, type Page } from "@playwright/test";

const browserErrors = new WeakMap<Page, string[]>();

test.beforeEach(async ({ page }) => {
  const errors: string[] = [];
  browserErrors.set(page, errors);

  page.on("console", (message) => {
    if (message.type() === "error") {
      const expectedUnauthenticatedSession =
        message
          .location()
          .url.includes("/api/v1/me") &&
        message
          .text()
          .includes("the server responded with a status of 401");
      if (expectedUnauthenticatedSession) return;
      errors.push(
        `console: ${message.text()} (${message.location().url || "unknown source"})`,
      );
    }
  });
  page.on("pageerror", (error) => {
    errors.push(`pageerror: ${error.message}`);
  });
});

test.afterEach(async ({ page }) => {
  expect(
    browserErrors.get(page) ?? [],
    "The browser emitted console errors or uncaught page errors.",
  ).toEqual([]);
});
