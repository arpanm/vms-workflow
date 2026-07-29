import { createFileRoute } from "@tanstack/react-router";

import { PageHeader } from "@/components/page-header";
import { AuthorityNotice } from "@/features/certification/components";
import { useCertificationInbox } from "@/features/certification/hooks";
import { CertificationInboxPanel } from "@/features/certification/inbox";
import { MonthAccess } from "@/features/certification/month-access";

export const Route = createFileRoute("/certification/")({
  head: () => ({ meta: [{ title: "Certification — Cadence" }] }),
  component: CertificationEntryPage,
});

function CertificationEntryPage() {
  const inbox = useCertificationInbox();

  return (
    <div>
      <PageHeader
        title="Delivery certification"
        description="Open a scoped engagement month for vendor submission or product-owner certification."
      />
      <div className="space-y-4 p-6">
        <AuthorityNotice />
        {inbox.isPending && (
          <p role="status" className="rounded-lg border p-4 text-sm text-muted-foreground">
            Loading your server-scoped certification work…
          </p>
        )}
        {inbox.isError && (
          <p role="alert" className="rounded-lg border border-destructive/30 p-4 text-sm">
            Certification work could not be loaded. Use direct month access if you already have an
            authorized month reference.
          </p>
        )}
        {inbox.data && <CertificationInboxPanel inbox={inbox.data} />}
        <MonthAccess
          destination="/certification/$monthId"
          title="Open a specific month"
          detail="The server resolves the effective frozen baseline, active permissions, project scope, and current version. A route does not grant authority."
        />
      </div>
    </div>
  );
}
