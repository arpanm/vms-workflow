import type {
  FinancePermission,
  FreshnessStatus,
  InvoiceState,
} from "./contracts";

const terminalInvoiceStates: ReadonlySet<InvoiceState> = new Set([
  "SUPERSEDED",
  "CANCELLED",
  "CLOSED",
]);

export function canViewPaymentTimeline(permissions: FinancePermission[]) {
  return permissions.includes("PAYMENT_VIEW");
}

export function invoiceCommandsDisabled(invoice: {
  readOnly: boolean;
  freshness: FreshnessStatus;
  state: InvoiceState;
}) {
  return (
    invoice.readOnly ||
    invoice.freshness === "STALE" ||
    terminalInvoiceStates.has(invoice.state)
  );
}

export function monthCommandsDisabled(month: {
  freshness: FreshnessStatus;
}) {
  return month.freshness === "STALE";
}

/**
 * Treat server-provided remediation paths as display data until they pass a
 * narrow same-application allowlist. API authorization remains authoritative.
 */
export function safeFinanceActionPath(path: string | null) {
  const hasUnsafeCharacter = path
    ? path.includes("\\") || [...path].some((character) => character.charCodeAt(0) < 32)
    : false;
  if (!path || path.startsWith("//") || hasUnsafeCharacter) {
    return null;
  }
  try {
    const parsed = new URL(path, "https://finance.local");
    if (
      parsed.origin !== "https://finance.local" ||
      (parsed.pathname !== "/finance" && !parsed.pathname.startsWith("/finance/"))
    ) {
      return null;
    }
    return `${parsed.pathname}${parsed.search}${parsed.hash}`;
  } catch {
    return null;
  }
}

export function localDateTimeToIso(value: string) {
  if (!value) return null;
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
}

export function formatDateTime(value: string | null) {
  if (!value) return "Unavailable";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "Unavailable" : date.toLocaleString();
}

export function shortenHash(value: string) {
  return value.length > 20 ? `${value.slice(0, 12)}…${value.slice(-8)}` : value;
}
