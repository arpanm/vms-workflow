import { describe, expect, it } from "vitest";

import {
  normalizeControlTower,
  normalizeDashboard,
  normalizeReports,
} from "./adapters";

describe("finance backend response adapters", () => {
  it("[F05-UNIT-CONTRACT-001] maps backend dashboard queue names to the UI contract", () => {
    const result = normalizeDashboard({
      personaLabel: "Procurement",
      refreshedAt: "2026-07-27T00:00:00Z",
      freshness: "CURRENT",
      permissions: ["REPORT_VIEW"],
      metrics: [{
        metricCode: "PAYMENT_STATUS",
        displayName: "Payment status",
        value: 3,
        version: 1,
        availability: "AVAILABLE",
        freshness: "CURRENT",
        sourceLabel: "Payment history",
      }],
      queues: [{
        code: "PAYMENT",
        label: "Payment status available",
        count: 3,
        path: "/finance/invoices",
      }],
    });

    expect(result.metrics[0]).toMatchObject({
      metricId: "PAYMENT_STATUS",
      displayValue: "3",
      unavailable: false,
    });
    expect(result.queues[0]).toEqual({
      key: "PAYMENT",
      label: "Payment status available",
      count: 3,
      actionPath: "/finance",
    });
  });

  it("[F05-UNIT-CONTRACT-002] expands the flattened control row without inventing source facts", () => {
    const result = normalizeControlTower({
      permissions: ["PROCUREMENT_REVIEW"],
      refreshedAt: "2026-07-27T00:00:00Z",
      freshness: "CURRENT",
      rows: {
        items: [{
          monthId: "month-1",
          monthLabel: "2026-07",
          engagementLabel: "Engagement",
          invoiceId: "invoice-1",
          invoiceNumber: "INV-1",
          invoiceState: "PROCUREMENT_REVIEW",
          packageState: "CURRENT",
          packageVersion: 2,
          paymentStatus: null,
          freshness: "CURRENT",
          ownerDisplay: "Scoped owner",
        }],
        nextCursor: null,
        totalCount: 1,
      },
    });

    expect(result.rows.items[0].cells).toHaveLength(9);
    expect(result.rows.items[0].cells[0]).toMatchObject({
      key: "ROSTER",
      state: "WARNING",
      sourceLabel: "Source detail unavailable from the current API",
    });
    expect(result.rows.items[0].cells.find((cell) => cell.key === "PACKAGE"))
      .toMatchObject({ state: "COMPLETE", version: "2" });
  });

  it("[F05-UNIT-CONTRACT-003] maps report definitions and export job fields", () => {
    const result = normalizeReports({
      permissions: ["REPORT_VIEW", "REPORT_EXPORT"],
      definitions: [{
        reportId: "INVOICE_READINESS",
        version: "v1",
        label: "Invoice readiness",
        formats: ["CSV", "JSON"],
        temporalModes: ["CURRENT", "SNAPSHOT"],
      }],
      exports: {
        items: [{
          exportId: "export-1",
          reportId: "INVOICE_READINESS",
          reportVersion: "v1",
          format: "CSV",
          status: "PENDING",
          progressPercent: 0,
          completedAt: null,
          expiresAt: null,
          rowCount: null,
          resultHash: null,
          sourceFreshnessAt: "2026-07-27T00:00:00Z",
          temporalMode: "CURRENT",
          filters: "{\"month\":\"2026-07\"}",
          downloadAllowed: false,
          correlationId: "corr-1",
        }],
        nextCursor: null,
        totalCount: 1,
      },
    });

    expect(result.definitions[0]).toMatchObject({
      name: "Invoice readiness",
      availableFormats: ["CSV", "JSON"],
      snapshotMode: "SELECTABLE",
    });
    expect(result.exports.items[0]).toMatchObject({
      reportName: "Invoice readiness",
      status: "QUEUED",
      sha256: null,
      sourceFreshness: "CURRENT",
    });
  });
});
