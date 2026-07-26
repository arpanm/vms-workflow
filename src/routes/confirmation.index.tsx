import { createFileRoute } from "@tanstack/react-router";

import { PageHeader } from "@/components/page-header";
import { AuthorityNotice } from "@/features/certification/components";
import { MonthAccess } from "@/features/certification/month-access";

export const Route = createFileRoute("/confirmation/")({
  head: () => ({ meta: [{ title: "Confirmation control — Cadence" }] }),
  component: ConfirmationEntryPage,
});

function ConfirmationEntryPage() {
  return (
    <div>
      <PageHeader
        title="Confirmation control"
        description="Review five-pillar readiness, exact recipient/version preview, request lineage, and governed reopen impact."
      />
      <div className="space-y-4 p-6">
        <AuthorityNotice />
        <MonthAccess
          destination="/confirmation/$monthId"
          title="Open readiness and governance"
          detail="Readiness is recalculated by the server from immutable plan, attendance, certification, and confirmation versions."
        />
      </div>
    </div>
  );
}
