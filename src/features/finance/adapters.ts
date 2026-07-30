import type {
  ControlTowerView,
  DashboardMetric,
  ExportJob,
  FinanceDashboard,
  FinancePermission,
  FreshnessStatus,
  JobStatus,
  MatrixCell,
  MatrixState,
  ReportDefinition,
  ReportsWorkspace,
} from "./contracts";

type JsonObject = Record<string, unknown>;

function object(value: unknown): JsonObject {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as JsonObject
    : {};
}

function array(value: unknown) {
  return Array.isArray(value) ? value : [];
}

function text(value: unknown, fallback = "") {
  return typeof value === "string" ? value : fallback;
}

function number(value: unknown, fallback = 0) {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

function nullableText(value: unknown) {
  return typeof value === "string" && value.length > 0 ? value : null;
}

function permissions(value: unknown) {
  return array(value).filter((item): item is FinancePermission =>
    typeof item === "string",
  );
}

function freshness(value: unknown): FreshnessStatus {
  return value === "CURRENT" || value === "STALE" || value === "UNKNOWN"
    ? value
    : "UNKNOWN";
}

function matrixCell(
  key: MatrixCell["key"],
  label: string,
  state: MatrixState,
  row: JsonObject,
  options: { version?: unknown; sourceLabel?: string; actionPath?: string | null } = {},
): MatrixCell {
  return {
    key,
    label,
    state,
    ownerDisplay: nullableText(row.ownerDisplay),
    version: options.version === null || options.version === undefined
      ? null
      : String(options.version),
    freshness: freshness(row.freshness),
    temporalMode: "LIVE",
    sourceLabel: options.sourceLabel ?? "Source detail unavailable from the current API",
    actionPath: options.actionPath ?? null,
  };
}

function packageState(row: JsonObject): MatrixState {
  if (row.packageState == null) return "NOT_APPLICABLE";
  if (row.packageState === "CURRENT") return "COMPLETE";
  if (row.packageState === "INVALIDATED") return "STALE";
  if (row.packageState === "FAILED") return "BLOCKING";
  return "WARNING";
}

function invoiceState(row: JsonObject): MatrixState {
  if (row.invoiceState == null) return "NOT_APPLICABLE";
  if (["READY_FOR_VENDOR_SUBMISSION", "APPROVED_FOR_PROCESSING", "PAID", "CLOSED"]
    .includes(String(row.invoiceState))) return "COMPLETE";
  if (["EVIDENCE_PENDING", "CHANGES_REQUESTED", "ON_HOLD", "REJECTED"]
    .includes(String(row.invoiceState))) return "BLOCKING";
  if (row.invoiceState === "EXCEPTION_ACCEPTED") return "EXCEPTION_ACCEPTED";
  return "WARNING";
}

function paymentState(row: JsonObject): MatrixState {
  if (row.paymentStatus == null) return "NOT_APPLICABLE";
  if (row.paymentStatus === "PAID") return "COMPLETE";
  if (row.paymentStatus === "PAYMENT_FAILED" || row.paymentStatus === "ON_HOLD") {
    return "BLOCKING";
  }
  return "WARNING";
}

export function normalizeControlTower(value: unknown): ControlTowerView {
  const raw = object(value);
  const rawPage = object(raw.rows);
  const items = array(rawPage.items).map((item) => {
    const row = object(item);
    const monthId = text(row.monthId);
    const invoiceId = nullableText(row.invoiceId);
    const stale = freshness(row.freshness) === "STALE";
    const ownerPath = invoiceId
      ? `/finance/procurement?invoiceId=${encodeURIComponent(invoiceId)}`
      : `/finance?monthId=${encodeURIComponent(monthId)}`;
    const unavailableState: MatrixState = stale ? "STALE" : "WARNING";
    const cells: MatrixCell[] = [
      matrixCell("ROSTER", "Roster", unavailableState, row),
      matrixCell("ATTENDANCE", "Attendance", unavailableState, row),
      matrixCell("PLAN", "Plan", unavailableState, row),
      matrixCell("LINEAR", "Linear", unavailableState, row),
      matrixCell("CERTIFICATION", "Certification", unavailableState, row),
      matrixCell("CONFIRMATION", "Confirmation", unavailableState, row),
      matrixCell("PACKAGE", "Package", stale ? "STALE" : packageState(row), row, {
        version: row.packageVersion,
        sourceLabel: "F05 immutable package",
        actionPath: ownerPath,
      }),
      matrixCell("INVOICE", "Invoice", stale ? "STALE" : invoiceState(row), row, {
        sourceLabel: "F05 invoice state",
        actionPath: ownerPath,
      }),
      matrixCell("PAYMENT", "Payment", stale ? "STALE" : paymentState(row), row, {
        sourceLabel: "Append-only payment status",
        actionPath: ownerPath,
      }),
    ];
    return {
      monthId,
      monthLabel: text(row.monthLabel),
      engagementLabel: text(row.engagementLabel),
      invoiceId,
      invoiceNumber: nullableText(row.invoiceNumber),
      queue: text(row.invoiceState, "Evidence required").replaceAll("_", " ").toLowerCase(),
      ageDays: null,
      cells,
    };
  });
  return {
    permissions: permissions(raw.permissions),
    refreshedAt: text(raw.refreshedAt),
    freshness: freshness(raw.freshness),
    rows: {
      items,
      nextCursor: nullableText(rawPage.nextCursor),
      totalCount: typeof rawPage.totalCount === "number" ? rawPage.totalCount : items.length,
    },
  };
}

export function normalizeDashboard(value: unknown): FinanceDashboard {
  const raw = object(value);
  const refreshedAt = text(raw.refreshedAt);
  const metrics: DashboardMetric[] = array(raw.metrics).map((item) => {
    const metric = object(item);
    const unavailable = typeof metric.availability === "string"
      ? metric.availability !== "AVAILABLE"
      : metric.unavailable === true;
    return {
      metricId: text(metric.metricCode, text(metric.metricId)),
      label: text(
        metric.displayName,
        text(metric.label, text(metric.metricCode, text(metric.metricId))),
      ),
      displayValue: String(metric.value ?? metric.displayValue ?? ""),
      unavailable,
      definitionVersion: String(
        metric.version ?? metric.definitionVersion ?? "unavailable",
      ),
      policyVersion: text(metric.policyVersion, "f05-metric-dictionary"),
      sourceLabel: text(metric.sourceLabel, "F05 scoped facts"),
      freshness: freshness(metric.freshness),
      temporalMode: metric.temporalMode === "SNAPSHOT" ? "SNAPSHOT" : "LIVE",
      refreshedAt: text(metric.refreshedAt, refreshedAt),
    };
  });
  return {
    personaLabel: text(raw.personaLabel, "Scoped finance"),
    refreshedAt,
    freshness: freshness(raw.freshness),
    metrics,
    queues: array(raw.queues).map((item) => {
      const queue = object(item);
      const rawPath = nullableText(queue.path ?? queue.actionPath);
      return {
        key: text(queue.code, text(queue.key)),
        label: text(queue.label),
        count: number(queue.count),
        actionPath: rawPath === "/finance/invoices" ? "/finance" : rawPath,
      };
    }),
    permissions: permissions(raw.permissions),
  };
}

function jobStatus(value: unknown): JobStatus {
  if (value === "PENDING") return "QUEUED";
  if (value === "CLAIMED") return "RUNNING";
  if (
    value === "READY" || value === "FAILED" || value === "DEAD_LETTER" ||
    value === "EXPIRED" || value === "QUEUED" || value === "RUNNING" ||
    value === "RETRY_SCHEDULED"
  ) return value;
  return "FAILED";
}

export function normalizeReports(value: unknown): ReportsWorkspace {
  const raw = object(value);
  const definitions: ReportDefinition[] = array(raw.definitions).map((item) => {
    const definition = object(item);
    const modes = array(definition.temporalModes);
    return {
      reportId: text(definition.reportId),
      name: text(definition.label, text(definition.name)),
      version: text(definition.version),
      description: text(
        definition.description,
        `Versioned server report: ${text(definition.label, text(definition.reportId))}.`,
      ),
      availableFormats: array(definition.formats ?? definition.availableFormats)
        .filter((format): format is ReportDefinition["availableFormats"][number] =>
          format === "CSV" || format === "XLSX" || format === "PDF" || format === "JSON",
        ),
      snapshotMode: definition.snapshotMode === "SELECTABLE" || modes.length > 1
        ? "SELECTABLE"
        : definition.snapshotMode === "SNAPSHOT" || modes[0] === "SNAPSHOT"
          ? "SNAPSHOT"
          : "CURRENT",
    };
  });
  const rawPage = object(raw.exports);
  const exports: ExportJob[] = array(rawPage.items).map((item) => {
    const job = object(item);
    const reportId = text(job.reportId);
    return {
      exportId: text(job.exportId),
      reportId,
      reportName: definitions.find((definition) =>
        definition.reportId === reportId)?.name ?? reportId,
      reportVersion: text(job.reportVersion),
      format: text(job.format, "JSON") as ExportJob["format"],
      status: jobStatus(job.status),
      progressPercent: number(job.progressPercent),
      generatedAt: nullableText(job.completedAt ?? job.generatedAt),
      expiresAt: nullableText(job.expiresAt),
      rowCount: typeof job.rowCount === "number" ? job.rowCount : null,
      sha256: nullableText(job.resultHash ?? job.sha256),
      sourceFreshness: job.sourceFreshnessAt ? "CURRENT" : "UNKNOWN",
      temporalMode: job.temporalMode === "SNAPSHOT" ? "SNAPSHOT" : "LIVE",
      filterSummary: text(job.filters ?? job.filterSummary, "{}"),
      downloadAllowed: job.downloadAllowed === true,
      correlationId: nullableText(job.correlationId),
    };
  });
  return {
    permissions: permissions(raw.permissions),
    definitions,
    exports: {
      items: exports,
      nextCursor: nullableText(rawPage.nextCursor),
      totalCount: typeof rawPage.totalCount === "number"
        ? rawPage.totalCount
        : exports.length,
    },
  };
}
