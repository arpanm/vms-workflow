import { createFileRoute } from "@tanstack/react-router";
import { PageHeader } from "@/components/page-header";
import { Card, CardContent } from "@/components/ui/card";
import { StatusBadge } from "@/components/status-badge";
import { useApprovals, useRequirements } from "@/lib/data-hooks";
import { requireLegacyRoute } from "@/lib/legacy-route";
import { QueryState } from "@/components/query-state";

export const Route = createFileRoute("/approvals")({
  beforeLoad: requireLegacyRoute,
  head: () => ({
    meta: [
      { title: "Approvals — Cadence" },
      { name: "description", content: "Read-only legacy approval queue and decision history." },
    ],
  }),
  component: ApprovalsPage,
});

function hoursSince(iso: string) {
  return Math.round((Date.now() - new Date(iso).getTime()) / 36e5);
}

function ApprovalsPage() {
  const approvalsQuery = useApprovals();
  const requirementsQuery = useRequirements();
  const approvals = approvalsQuery.data ?? [];
  const requirements = requirementsQuery.data ?? [];

  const pending = approvals.filter((a) => a.status === "pending");
  const decided = approvals.filter((a) => a.status !== "pending");

  return (
    <div>
      <PageHeader
        title="Approvals"
        description="Legacy decisions remain visible for audit. Pending items require an explicit authorized human decision in the canonical workflow."
      />
      <QueryState queries={[approvalsQuery, requirementsQuery]}>
      <div className="space-y-6 p-6">
        <Section title={`Pending (${pending.length})`}>
          {pending.length === 0 ? (
            <Empty text="No items waiting for approval." />
          ) : (
            <Card className="border-border/60 shadow-[var(--shadow-card)]">
              <CardContent className="p-0">
                <ul className="divide-y divide-border/60">
                  {pending.map((a) => {
                    const r = requirements.find((x) => x.id === a.requirement_id);
                    const age = hoursSince(a.requested_at);
                    const breached = age > a.sla_hours;
                    return (
                      <li key={a.id} className="flex items-center gap-3 p-4 hover:bg-muted/30">
                        <div className="flex-1 min-w-0">
                          <p className="font-medium text-sm truncate">{r?.title ?? "—"}</p>
                          <p className="text-xs text-muted-foreground mt-0.5">
                            Approver: <span className="font-medium text-foreground">{a.approver}</span> · Open {age}h
                            {breached && <span className="ml-2 text-destructive font-medium">SLA breached</span>}
                          </p>
                        </div>
                        <span className="text-xs text-muted-foreground">
                          Read-only legacy record
                        </span>
                      </li>
                    );
                  })}
                </ul>
              </CardContent>
            </Card>
          )}
        </Section>

        <Section title="Recently decided">
          <Card className="border-border/60 shadow-[var(--shadow-card)]">
            <CardContent className="p-0">
              <ul className="divide-y divide-border/60">
                {decided.slice(0, 12).map((a) => {
                  const r = requirements.find((x) => x.id === a.requirement_id);
                  return (
                    <li key={a.id} className="flex items-center gap-3 p-4">
                      <div className="flex-1 min-w-0">
                        <p className="font-medium text-sm truncate">{r?.title ?? "—"}</p>
                        <p className="text-xs text-muted-foreground mt-0.5">By {a.approver}</p>
                      </div>
                      <StatusBadge status={a.status} />
                    </li>
                  );
                })}
              </ul>
            </CardContent>
          </Card>
        </Section>
      </div>
      </QueryState>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="space-y-3">
      <h2 className="text-sm font-semibold uppercase tracking-wider text-muted-foreground">{title}</h2>
      {children}
    </div>
  );
}
function Empty({ text }: { text: string }) {
  return <Card className="border-dashed"><CardContent className="py-12 text-center text-sm text-muted-foreground">{text}</CardContent></Card>;
}
