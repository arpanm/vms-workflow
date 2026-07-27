import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { DashboardQueues, PaymentTimeline } from "./components";
import {
  canViewPaymentTimeline,
  invoiceCommandsDisabled,
  localDateTimeToIso,
  monthCommandsDisabled,
  safeFinanceActionPath,
} from "./presentation";

describe("finance presentation policy", () => {
  it("[F05-UNIT-PRES-001] requires the explicit payment-view capability", () => {
    expect(canViewPaymentTimeline(["INVOICE_VIEW"])).toBe(false);
    expect(canViewPaymentTimeline(["INVOICE_VIEW", "PAYMENT_VIEW"])).toBe(true);
  });

  it("[F05-UNIT-PRES-004] does not render payment facts without payment-view authority", () => {
    const events = [{
      paymentEventId: "payment-1",
      version: 1,
      status: "PAID" as const,
      source: "ERP" as const,
      provenance: "restricted ERP fixture",
      comment: "sensitive timeline comment",
      externalReference: "erp-123",
      statusAt: "2026-07-27T00:00:00Z",
      expectedPaymentDate: null,
      actualPaymentDate: "2026-07-27",
      recordedAt: "2026-07-27T00:01:00Z",
      recordedByDisplay: "Finance AP",
    }];
    const restricted = renderToStaticMarkup(createElement(PaymentTimeline, {
      permissions: ["INVOICE_VIEW"],
      events,
    }));
    const allowed = renderToStaticMarkup(createElement(PaymentTimeline, {
      permissions: ["INVOICE_VIEW", "PAYMENT_VIEW"],
      events,
    }));

    expect(restricted).toContain("Payment timeline restricted");
    expect(restricted).not.toContain("sensitive timeline comment");
    expect(restricted).not.toContain("restricted ERP fixture");
    expect(allowed).toContain("sensitive timeline comment");
  });

  it("[F05-UNIT-PRES-002] blocks commands for read-only, stale, and terminal invoices", () => {
    expect(invoiceCommandsDisabled({
      readOnly: true,
      freshness: "CURRENT",
      state: "UPLOADED",
    })).toBe(true);
    expect(invoiceCommandsDisabled({
      readOnly: false,
      freshness: "STALE",
      state: "UPLOADED",
    })).toBe(true);
    expect(invoiceCommandsDisabled({
      readOnly: false,
      freshness: "CURRENT",
      state: "SUPERSEDED",
    })).toBe(true);
    expect(invoiceCommandsDisabled({
      readOnly: false,
      freshness: "CURRENT",
      state: "UPLOADED",
    })).toBe(false);
    expect(monthCommandsDisabled({ freshness: "STALE" })).toBe(true);
  });

  it("[F05-UNIT-PRES-003] accepts only same-application finance action paths", () => {
    expect(safeFinanceActionPath("/finance?monthId=month-1")).toBe(
      "/finance?monthId=month-1",
    );
    expect(safeFinanceActionPath("/finance/procurement?invoiceId=inv-1")).toBe(
      "/finance/procurement?invoiceId=inv-1",
    );
    expect(safeFinanceActionPath("https://attacker.example/finance")).toBeNull();
    expect(safeFinanceActionPath("//attacker.example/finance")).toBeNull();
    expect(safeFinanceActionPath("/admin")).toBeNull();
  });

  it("[F05-UNIT-PRES-005] renders queue counts while dropping unsafe CTAs", () => {
    const markup = renderToStaticMarkup(createElement(DashboardQueues, {
      queues: [
        { key: "review", label: "Review queue", count: 2, actionPath: "/finance/procurement" },
        { key: "unsafe", label: "Unsafe queue", count: 1, actionPath: "https://attacker.example" },
      ],
    }));

    expect(markup).toContain("Review queue");
    expect(markup).toContain('href="/finance/procurement"');
    expect(markup).toContain("Unsafe queue");
    expect(markup).not.toContain("attacker.example");
  });

  it("[F05-UNIT-PRES-006] converts browser local timestamps to API offset timestamps", () => {
    const result = localDateTimeToIso("2026-07-27T12:30");
    expect(result).toMatch(/^2026-07-27T\d{2}:\d{2}:00\.000Z$/);
    expect(localDateTimeToIso("")).toBeNull();
    expect(localDateTimeToIso("not-a-date")).toBeNull();
  });
});
