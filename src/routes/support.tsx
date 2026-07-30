import { createFileRoute, Link } from "@tanstack/react-router";
import { CircleHelp, ExternalLink, ShieldAlert } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

export const Route = createFileRoute("/support")({
  head: () => ({
    meta: [
      { title: "Role guides and support — Cadence" },
      {
        name: "description",
        content:
          "Role-specific workflow guidance, escalation paths and safe support information.",
      },
    ],
  }),
  component: SupportPage,
});

const guides = [
  {
    role: "Employee",
    start: "/attendance/today",
    actions: "Record attendance, apply leave and submit regularization requests.",
    escalation:
      "Contact your vendor HR administrator for calendar, roster, leave balance or missing-scope corrections.",
  },
  {
    role: "Vendor HR / administrator",
    start: "/workforce/administration",
    actions:
      "Maintain employee, allocation, calendar and leave-policy facts; resolve month-close blockers.",
    escalation:
      "Use correlation IDs when escalating provider synchronization, roster or immutable-history conflicts.",
  },
  {
    role: "Vendor delivery",
    start: "/delivery/plans",
    actions:
      "Draft delivery plans, declare evidence, submit outcomes and answer clarification requests.",
    escalation:
      "Request a governed revision or reopen; never edit frozen or certified evidence in place.",
  },
  {
    role: "Product owner",
    start: "/certification",
    actions:
      "Review delivery evidence, record explicit certification decisions and respond to confirmation requests.",
    escalation:
      "Route conflicts, missing evidence and separation-of-duties exceptions to governance.",
  },
  {
    role: "Procurement / finance",
    start: "/finance/procurement",
    actions:
      "Review immutable packages and invoices, record exceptions, payment facts and restricted exports.",
    escalation:
      "Place a hold when lineage, scan, retention, masking or confirmation readiness is unclear.",
  },
  {
    role: "Integration administrator",
    start: "/delivery/integration-health",
    actions:
      "Monitor provider freshness, reconciliation, queues, dead letters and bounded replay.",
    escalation:
      "Keep provider state explicitly stale during outages; do not infer approval from provider or transport state.",
  },
  {
    role: "Governance / reopen",
    start: "/administration/approval-requests",
    actions:
      "Decide scoped approval and reopen requests using independent authority and exact-version evidence.",
    escalation:
      "Reject self-approval, ambiguous scope and incomplete impact declarations.",
  },
  {
    role: "Migration operator",
    start: "/migration",
    actions:
      "Stage, scan, validate, reconcile and commit historical data with dual authorization.",
    escalation:
      "Cancel or pause unsafe work; preserve the batch, validation report and correlation ID for investigation.",
  },
] as const;

function SupportPage() {
  return (
    <div>
      <PageHeader
        title="Role guides and support"
        description="Choose the workflow for your active role. Authority is always enforced by the server and current scope."
      />
      <div className="space-y-6 p-6">
        <Card className="border-warning/40 bg-warning/10">
          <CardContent className="flex gap-3 py-4 text-sm">
            <ShieldAlert className="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true" />
            <div>
              <p className="font-medium">Safe support rule</p>
              <p className="mt-1 text-muted-foreground">
                Do not send credentials, tokens, raw email, restricted artifacts or
                personal data to support. Share the visible correlation ID, scoped
                record identifier and action time instead.
              </p>
            </div>
          </CardContent>
        </Card>

        <section aria-labelledby="role-guide-heading">
          <div className="mb-3 flex items-center gap-2">
            <CircleHelp className="h-5 w-5" aria-hidden="true" />
            <h2 id="role-guide-heading" className="text-lg font-semibold">
              Start by role
            </h2>
          </div>
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            {guides.map((guide) => (
              <Card key={guide.role} className="border-border/60">
                <CardHeader className="pb-2">
                  <CardTitle className="text-base">{guide.role}</CardTitle>
                </CardHeader>
                <CardContent className="space-y-3 text-sm">
                  <p>{guide.actions}</p>
                  <p className="text-muted-foreground">{guide.escalation}</p>
                  <Link
                    to={guide.start}
                    className="inline-flex items-center gap-1 font-medium text-primary underline-offset-4 hover:underline"
                  >
                    Open workspace
                    <ExternalLink className="h-3.5 w-3.5" aria-hidden="true" />
                  </Link>
                </CardContent>
              </Card>
            ))}
          </div>
        </section>

        <Card>
          <CardHeader>
            <CardTitle className="text-base">When an action fails</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 text-sm text-muted-foreground">
            <p>
              Refresh the record before retrying a version conflict. A repeated
              idempotency key is only for retrying the same intended action.
            </p>
            <p>
              For permission denial, verify the active organization, engagement and
              month; UI visibility does not grant authority.
            </p>
            <p>
              For provider, scan or queue failures, retain the correlation ID and use
              the appropriate health workspace. Never bypass a readiness blocker.
            </p>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
