import { createFileRoute } from "@tanstack/react-router";
import { useMemo, useState } from "react";
import { PageHeader } from "@/components/page-header";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Progress } from "@/components/ui/progress";
import { StatusBadge, PriorityBadge } from "@/components/status-badge";
import { useEngagements, useRequirements, type Requirement } from "@/lib/data-hooks";
import { Boxes, Sparkles, ArrowRight, Lock, Clock } from "lucide-react";

export const Route = createFileRoute("/scope")({
  head: () => ({
    meta: [
      { title: "Monthly Scope Engine — Cadence" },
      { name: "description", content: "Auto-finalize next month scope across vendors using stack rank, capacity and reserved buffers." },
    ],
  }),
  component: ScopePage,
});

const RESERVED = { enhancements: 0.7, support: 0.15, critical: 0.10, innovation: 0.05 };

function ScopePage() {
  const { data: requirements = [] } = useRequirements();
  const { data: engagements = [] } = useEngagements();
  const [selectedEng, setSelectedEng] = useState<string | null>(null);

  // Simple greedy scope finalizer per engagement
  const proposals = useMemo(() => {
    return engagements.map((e) => {
      const enhancementCap = Math.floor(e.monthly_capacity_hours * RESERVED.enhancements);
      const candidates = requirements
        .filter((r) => r.engagement_id === e.id && ["submitted", "estimated", "approved", "draft"].includes(r.status))
        .sort((a, b) => (a.priority.localeCompare(b.priority)) || a.rank - b.rank);

      const planned: Requirement[] = [];
      const carry: Requirement[] = [];
      let used = 0;
      for (const r of candidates) {
        const ready = !!r.acceptance_criteria && !!r.uat_cases && !!r.business_owner;
        if (!ready) { carry.push(r); continue; }
        if (used + r.estimated_hours <= enhancementCap) {
          planned.push(r);
          used += r.estimated_hours;
        } else {
          carry.push(r);
        }
      }
      return {
        engagement: e,
        enhancementCap,
        used,
        planned,
        carry,
        utilizationPct: enhancementCap ? Math.round((used / enhancementCap) * 100) : 0,
      };
    });
  }, [engagements, requirements]);

  const active = selectedEng
    ? proposals.find((p) => p.engagement.id === selectedEng)
    : proposals[0];

  return (
    <div>
      <PageHeader
        title="Monthly Scope Engine"
        description="Auto-finalizes next month's scope by stack rank within each vendor's bandwidth. Reserved buffers protect production support, critical fixes and innovation."
      >
        <Button size="sm" variant="outline" className="gap-1.5"><Lock className="h-4 w-4" /> Freeze (15th)</Button>
        <Button size="sm" className="gap-1.5"><Sparkles className="h-4 w-4" /> Run optimizer</Button>
      </PageHeader>

      <div className="grid gap-6 p-6 lg:grid-cols-[280px_1fr]">
        {/* Engagement list */}
        <div className="space-y-2">
          <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground px-1">Engagements</p>
          {proposals.map((p) => {
            const isActive = active?.engagement.id === p.engagement.id;
            return (
              <button
                key={p.engagement.id}
                onClick={() => setSelectedEng(p.engagement.id)}
                className={`w-full rounded-lg border p-3 text-left transition ${isActive ? "border-accent/60 bg-accent/5 shadow-sm" : "border-border/60 hover:border-border bg-card"}`}
              >
                <div className="flex items-center justify-between">
                  <span className="text-sm font-medium">{p.engagement.name}</span>
                  <span className="text-[10px] text-muted-foreground">{p.utilizationPct}%</span>
                </div>
                <Progress value={p.utilizationPct} className="mt-2 h-1" />
                <div className="mt-1.5 flex items-center justify-between text-[11px] text-muted-foreground">
                  <span>{p.planned.length} planned</span>
                  <span>{p.carry.length} carry-fwd</span>
                </div>
              </button>
            );
          })}
        </div>

        {/* Detail */}
        {active && (
          <div className="space-y-4 min-w-0">
            {/* Capacity allocation */}
            <Card className="border-border/60 shadow-[var(--shadow-card)] overflow-hidden">
              <div className="bg-[var(--gradient-hero)] p-5 text-primary-foreground">
                <div className="flex items-start justify-between gap-4">
                  <div>
                    <p className="text-xs uppercase tracking-wider opacity-80">Engagement</p>
                    <h2 className="text-lg font-semibold mt-1">{active.engagement.name}</h2>
                    <p className="text-xs opacity-80 mt-0.5">{active.engagement.vendor}</p>
                  </div>
                  <div className="text-right">
                    <p className="text-xs uppercase tracking-wider opacity-80">Monthly capacity</p>
                    <p className="text-2xl font-semibold mt-1">{active.engagement.monthly_capacity_hours} <span className="text-xs opacity-80 font-normal">hrs</span></p>
                  </div>
                </div>
              </div>
              <CardContent className="p-5">
                <div className="grid gap-3 sm:grid-cols-4">
                  <CapBlock label="Enhancements" value={`${Math.round(active.engagement.monthly_capacity_hours * RESERVED.enhancements)}h`} pct={70} tone="bg-accent/15 text-accent" />
                  <CapBlock label="Production support" value={`${Math.round(active.engagement.monthly_capacity_hours * RESERVED.support)}h`} pct={15} tone="bg-info/15 text-info-foreground" />
                  <CapBlock label="Critical fixes" value={`${Math.round(active.engagement.monthly_capacity_hours * RESERVED.critical)}h`} pct={10} tone="bg-warning/15 text-warning-foreground" />
                  <CapBlock label="Innovation buffer" value={`${Math.round(active.engagement.monthly_capacity_hours * RESERVED.innovation)}h`} pct={5} tone="bg-primary/10 text-primary" />
                </div>
              </CardContent>
            </Card>

            {/* Planned */}
            <Card className="border-border/60 shadow-[var(--shadow-card)]">
              <CardHeader className="pb-2">
                <div className="flex items-center justify-between">
                  <CardTitle className="text-base flex items-center gap-2">
                    <Boxes className="h-4 w-4 text-muted-foreground" /> Auto-planned scope
                  </CardTitle>
                  <span className="text-xs text-muted-foreground">{active.used}h / {active.enhancementCap}h enhancements</span>
                </div>
              </CardHeader>
              <CardContent className="p-0">
                {active.planned.length === 0 ? (
                  <p className="px-5 py-10 text-center text-sm text-muted-foreground">No items selected — backlog will be auto-filled from technical debt and automation queues.</p>
                ) : (
                  <ul className="divide-y divide-border/60">
                    {active.planned.map((r) => (
                      <li key={r.id} className="flex items-center justify-between gap-3 px-5 py-3 hover:bg-muted/30">
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-2">
                            <PriorityBadge priority={r.priority} />
                            <span className="text-sm font-medium truncate">{r.title}</span>
                          </div>
                          <p className="mt-0.5 text-xs text-muted-foreground truncate">{r.module} · {r.business_owner}</p>
                        </div>
                        <span className="text-sm tabular-nums text-muted-foreground whitespace-nowrap">{r.estimated_hours}h</span>
                        <StatusBadge status={r.status} />
                      </li>
                    ))}
                  </ul>
                )}
              </CardContent>
            </Card>

            {/* Carry forward */}
            {active.carry.length > 0 && (
              <Card className="border-border/60 shadow-[var(--shadow-card)]">
                <CardHeader className="pb-2">
                  <CardTitle className="text-base flex items-center gap-2">
                    <ArrowRight className="h-4 w-4 text-muted-foreground" /> Carry-forward / blocked
                  </CardTitle>
                </CardHeader>
                <CardContent className="p-0">
                  <ul className="divide-y divide-border/60">
                    {active.carry.map((r) => {
                      const incomplete = !r.acceptance_criteria || !r.uat_cases || !r.business_owner;
                      return (
                        <li key={r.id} className="flex items-center justify-between gap-3 px-5 py-3">
                          <div className="min-w-0 flex-1">
                            <div className="flex items-center gap-2">
                              <PriorityBadge priority={r.priority} />
                              <span className="text-sm font-medium truncate">{r.title}</span>
                            </div>
                            <p className="mt-0.5 text-xs text-muted-foreground">
                              {incomplete ? "Missing acceptance criteria / UAT cases / owner" : "Beyond capacity — auto carry-forward"}
                            </p>
                          </div>
                          <span className="inline-flex items-center gap-1 text-xs text-warning-foreground">
                            <Clock className="h-3.5 w-3.5 text-warning" /> Next cycle
                          </span>
                        </li>
                      );
                    })}
                  </ul>
                </CardContent>
              </Card>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

function CapBlock({ label, value, pct, tone }: { label: string; value: string; pct: number; tone: string }) {
  return (
    <div className="rounded-lg border border-border/60 p-3">
      <div className={`inline-flex items-center rounded-md px-1.5 py-0.5 text-[10px] font-medium ${tone}`}>{pct}%</div>
      <p className="mt-2 text-sm font-semibold">{value}</p>
      <p className="text-xs text-muted-foreground mt-0.5">{label}</p>
    </div>
  );
}
