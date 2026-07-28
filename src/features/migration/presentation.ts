import type { MigrationJob, MigrationPermission } from "./contracts";

export function can(
  job: Pick<MigrationJob, "permissions"> | null | undefined,
  permission: MigrationPermission,
) {
  return Boolean(job?.permissions.includes(permission));
}

export function safeIssueMessage(value: string) {
  return Array.from(value)
    .map((character) => {
      const codePoint = character.codePointAt(0) ?? 0;
      return codePoint < 32 || codePoint === 127 ? " " : character;
    })
    .join("")
    .slice(0, 300);
}

export function commitReadiness(job: MigrationJob) {
  const roles = new Set(job.reconciliation?.approvals.map((item) => item.role) ?? []);
  if (job.state !== "READY_TO_COMMIT") return "Validation must finish first.";
  if (!job.reconciliation) return "Generate the exact reconciliation first.";
  if (!roles.has("MIGRATION_LEAD")) return "Migration-lead approval is required.";
  if (!roles.has("GOVERNANCE_REVIEWER")) return "Distinct governance approval is required.";
  return null;
}

export function formatTimestamp(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? "Unavailable"
    : new Intl.DateTimeFormat("en-IN", {
        dateStyle: "medium",
        timeStyle: "short",
        timeZone: "Asia/Kolkata",
      }).format(date);
}
