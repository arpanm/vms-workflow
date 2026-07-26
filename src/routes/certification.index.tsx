import { createFileRoute } from "@tanstack/react-router";

import { PageHeader } from "@/components/page-header";
import { AuthorityNotice } from "@/features/certification/components";
import { MonthAccess } from "@/features/certification/month-access";

export const Route = createFileRoute("/certification/")({
  head: () => ({ meta: [{ title: "Certification — Cadence" }] }),
  component: CertificationEntryPage,
});

function CertificationEntryPage() {
  return (
    <div>
      <PageHeader
        title="Delivery certification"
        description="Open a scoped engagement month for vendor submission or product-owner certification."
      />
      <div className="space-y-4 p-6">
        <AuthorityNotice />
        <MonthAccess
          destination="/certification/$monthId"
          title="Open vendor and certification workspace"
          detail="The server resolves the effective frozen baseline, active permissions, project scope, and current version. A route does not grant authority."
        />
      </div>
    </div>
  );
}
