import { expect, test, type Page } from "@playwright/test";

const browserErrors = new WeakMap<Page, string[]>();
const allowedConsoleErrors = new WeakMap<Page, RegExp[]>();
const networkScans = new WeakMap<Page, Array<Promise<void>>>();
const restrictedContent =
  /plaintext-token=|token-hash=|boundary=unsafe|provider-secret=|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/i;

export function allowExpectedConsoleError(page: Page, pattern: RegExp) {
  const patterns = allowedConsoleErrors.get(page) ?? [];
  patterns.push(pattern);
  allowedConsoleErrors.set(page, patterns);
}

test.beforeEach(async ({ page }) => {
  const errors: string[] = [];
  browserErrors.set(page, errors);
  allowedConsoleErrors.set(page, []);
  networkScans.set(page, []);

  page.on("console", (message) => {
    const rendered = `${message.text()} ${message.location().url}`;
    if (restrictedContent.test(rendered)) {
      errors.push(`restricted console content: ${rendered}`);
    }
    if (message.type() === "error") {
      const expectedUnauthenticatedSession =
        message.location().url.includes("/api/v1/me") &&
        message.text().includes("the server responded with a status of 401");
      if (expectedUnauthenticatedSession) return;
      if ((allowedConsoleErrors.get(page) ?? []).some((pattern) => pattern.test(rendered))) {
        return;
      }
      errors.push(`console: ${message.text()} (${message.location().url || "unknown source"})`);
    }
  });
  page.on("pageerror", (error) => {
    if (restrictedContent.test(error.message)) {
      errors.push(`restricted page-error content: ${error.message}`);
    }
    errors.push(`pageerror: ${error.message}`);
  });

  page.on("request", (request) => {
    if (!request.url().includes("/api/v1/certification/")) return;
    const rendered = `${request.url()} ${request.postData() ?? ""}`;
    if (restrictedContent.test(rendered)) {
      errors.push(`restricted certification request content: ${rendered}`);
    }
  });

  page.on("response", (response) => {
    if (!response.url().includes("/api/v1/certification/")) return;
    const scan = response
      .text()
      .then((body) => {
        if (restrictedContent.test(body)) {
          errors.push(`restricted certification response content: ${response.url()}`);
        }
      })
      .catch(() => undefined);
    networkScans.get(page)?.push(scan);
  });
});

test.afterEach(async ({ page }) => {
  await Promise.all(networkScans.get(page) ?? []);
  expect(
    browserErrors.get(page) ?? [],
    "The browser emitted console errors or uncaught page errors.",
  ).toEqual([]);
});
