import { createFileRoute } from "@tanstack/react-router";

import { PageHeader } from "@/components/page-header";
import { AuthorityNotice } from "@/features/certification/components";
import {
  useCertificationInbox,
  useCertificationOperations,
} from "@/features/certification/hooks";
import {
  CertificationInboxPanel,
  CertificationOperationsPanel,
} from "@/features/certification/inbox";
import { MonthAccess } from "@/features/certification/month-access";

export const Route = createFileRoute("/confirmation/")({
  head: () => ({ meta: [{ title: "Confirmation control — Cadence" }] }),
  component: ConfirmationEntryPage,
});

function ConfirmationEntryPage() {
  const inbox = useCertificationInbox();
  const operations = useCertificationOperations();

  return (
    <div>
      <PageHeader
        title="Confirmation control"
        description="Review five-pillar readiness, exact recipient/version preview, request lineage, and governed reopen impact."
      />
      <div className="space-y-4 p-6">
        <AuthorityNotice />
        {(inbox.isPending || operations.isPending) && (
          <p role="status" className="rounded-lg border p-4 text-sm text-muted-foreground">
            Loading confirmation work and durable operations health…
          </p>
        )}
        {(inbox.isError || operations.isError) && (
          <p role="alert" className="rounded-lg border border-destructive/30 p-4 text-sm">
            Some governed confirmation data could not be loaded. No business decision or queue
            state has been inferred.
          </p>
        )}
        {inbox.data && <CertificationInboxPanel inbox={inbox.data} mode="CONFIRMATION" />}
        {operations.data && <CertificationOperationsPanel operations={operations.data} />}
        <MonthAccess
          destination="/confirmation/$monthId"
          title="Open a specific month"
          detail="Readiness is recalculated by the server from immutable plan, attendance, certification, and confirmation versions."
        />
      </div>
    </div>
  );
}
