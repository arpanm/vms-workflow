import { createFileRoute } from "@tanstack/react-router";

import { FinanceReportsWorkspace } from "@/features/finance/reports-workspace";

export const Route = createFileRoute("/finance/reports")({
  head: () => ({ meta: [{ title: "Finance reports and exports — Cadence" }] }),
  component: FinanceReportsWorkspace,
});
