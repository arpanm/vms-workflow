import { describe, expect, it } from "vitest";

import type { MigrationJob } from "./contracts";
import { commitReadiness, safeIssueMessage } from "./presentation";

const job = {
  state: "READY_TO_COMMIT",
  reconciliation: {
    reconciliationId: "r1",
    sha256: "a".repeat(64),
    approvals: [],
  },
} as unknown as MigrationJob;

describe("migration presentation controls", () => {
  it("requires both exact reconciliation approval roles", () => {
    expect(commitReadiness(job)).toMatch(/Migration-lead/);
    job.reconciliation!.approvals = [
      {
        approvalId: "a1",
        role: "MIGRATION_LEAD",
        actorDisplay: "Lead",
        recordedAt: "2026-07-27T00:00:00Z",
        reconciliationHash: "a".repeat(64),
      },
    ];
    expect(commitReadiness(job)).toMatch(/governance/);
    job.reconciliation!.approvals.push({
      approvalId: "a2",
      role: "GOVERNANCE_REVIEWER",
      actorDisplay: "Reviewer",
      recordedAt: "2026-07-27T00:01:00Z",
      reconciliationHash: "a".repeat(64),
    });
    expect(commitReadiness(job)).toBeNull();
  });

  it("removes controls and bounds issue copy", () => {
    expect(safeIssueMessage("unsafe\u0000row")).toBe("unsafe row");
    expect(safeIssueMessage("x".repeat(500))).toHaveLength(300);
  });
});
