import { createFileRoute } from "@tanstack/react-router";
import { PageHeader } from "@/components/page-header";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { StatusBadge } from "@/components/status-badge";
import { useApprovals, useRequirements } from "@/lib/data-hooks";
import { supabase } from "@/integrations/supabase/client";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Check, X, Zap } from "lucide-react";

export const Route = createFileRoute("/approvals")({
  head: () => ({
    meta: [
      { title: "Approvals — Cadence" },
      { name: "description", content: "Approval queue with SLA timers and auto-approval fallback for stuck items." },
    ],
  }),
  component: ApprovalsPage,
});

function hoursSince(iso: string) {
  return Math.round((Date.now() - new Date(iso).getTime()) / 36e5);
}

function ApprovalsPage() {
  const qc = useQueryClient();
  const { data: approvals = [] } = useApprovals();
  const { data: requirements = [] } = useRequirements();

  const act = async (id: string, status: "approved" | "rejected" | "auto_approved") => {
    const { error } = await supabase
      .from("approvals")
      .update({ status, acted_at: new Date().toISOString() } as any)
      .eq("id", id);
    if (error) { toast.error(error.message); return; }
    toast.success(`Approval ${status.replace("_"," ")}`);
    qc.invalidateQueries({ queryKey: ["approvals"] });
  };

  const pending = approvals.filter((a) => a.status === "pending");
  const decided = approvals.filter((a) => a.status !== "pending");

  return (
    <div>
      <PageHeader
        title="Approvals"
        description="48h SLA. After 72h system reminds, after 96h auto-approves to keep delivery momentum."
      />
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
                        <Button size="sm" variant="ghost" className="gap-1.5" onClick={() => act(a.id, "auto_approved")}>
                          <Zap className="h-4 w-4" /> Auto-approve
                        </Button>
                        <Button size="sm" variant="outline" className="gap-1.5" onClick={() => act(a.id, "rejected")}>
                          <X className="h-4 w-4" /> Reject
                        </Button>
                        <Button size="sm" className="gap-1.5" onClick={() => act(a.id, "approved")}>
                          <Check className="h-4 w-4" /> Approve
                        </Button>
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
