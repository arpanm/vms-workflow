import { createFileRoute } from "@tanstack/react-router";
import { PageHeader } from "@/components/page-header";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { StatusBadge } from "@/components/status-badge";
import { useRequirements, useUat } from "@/lib/data-hooks";
import { differenceInDays, parseISO } from "date-fns";
import { AlertTriangle, CheckCircle2, Clock } from "lucide-react";

export const Route = createFileRoute("/uat")({
  head: () => ({
    meta: [
      { title: "UAT — Cadence" },
      { name: "description", content: "Track UAT handover, defects and signoff. Auto deemed-acceptance after SLA to protect vendor cashflow." },
    ],
  }),
  component: UatPage,
});

function UatPage() {
  const { data: uat = [] } = useUat();
  const { data: requirements = [] } = useRequirements();

  const groups = {
    in_progress: uat.filter((u) => u.status === "in_progress"),
    blocked: uat.filter((u) => u.status === "blocked"),
    not_started: uat.filter((u) => u.status === "not_started"),
    signed_off: uat.filter((u) => ["signed_off", "deemed_accepted"].includes(u.status)),
  };

  return (
    <div>
      <PageHeader
        title="UAT Management"
        description="Mandatory UAT cases up front prevent endless back-and-forth. After 5 days of no response, items are auto deemed-accepted."
      />
      <div className="grid gap-4 p-6 md:grid-cols-2 xl:grid-cols-4">
        <Column title="In Progress" icon={Clock} tone="bg-info/15 text-info-foreground" items={groups.in_progress} requirements={requirements} />
        <Column title="Blocked" icon={AlertTriangle} tone="bg-destructive/15 text-destructive" items={groups.blocked} requirements={requirements} />
        <Column title="Not Started" icon={Clock} tone="bg-muted text-muted-foreground" items={groups.not_started} requirements={requirements} />
        <Column title="Signed Off" icon={CheckCircle2} tone="bg-success/15 text-success-foreground" items={groups.signed_off} requirements={requirements} />
      </div>
    </div>
  );
}

function Column({
  title, icon: Icon, tone, items, requirements,
}: { title: string; icon: any; tone: string; items: any[]; requirements: any[] }) {
  return (
    <Card className="border-border/60 shadow-[var(--shadow-card)] flex flex-col min-h-[320px]">
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center justify-between text-sm">
          <span className="flex items-center gap-2">
            <span className={`grid h-7 w-7 place-items-center rounded-md ${tone}`}><Icon className="h-3.5 w-3.5" /></span>
            {title}
          </span>
          <span className="text-xs font-normal text-muted-foreground">{items.length}</span>
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-2 flex-1 overflow-auto">
        {items.length === 0 && <p className="text-xs text-muted-foreground py-6 text-center">Empty</p>}
        {items.map((u) => {
          const r = requirements.find((x) => x.id === u.requirement_id);
          const days = u.handover_date ? differenceInDays(new Date(), parseISO(u.handover_date)) : 0;
          const aged = days >= 4 && u.status === "in_progress";
          return (
            <div key={u.id} className="rounded-lg border border-border/60 bg-muted/30 p-3">
              <p className="text-sm font-medium line-clamp-2">{r?.title ?? "—"}</p>
              <div className="mt-2 flex items-center justify-between">
                <span className="text-[11px] text-muted-foreground">Owner: {u.uat_owner || "—"}</span>
                <StatusBadge status={u.status} />
              </div>
              <div className="mt-2 flex items-center justify-between text-[11px]">
                <span className="text-muted-foreground">{u.defects_open} defects</span>
                <span className={aged ? "font-medium text-warning-foreground" : "text-muted-foreground"}>
                  {u.handover_date ? `${days}d in UAT` : u.signoff_date ? "Signed off" : "—"}
                </span>
              </div>
            </div>
          );
        })}
      </CardContent>
    </Card>
  );
}
