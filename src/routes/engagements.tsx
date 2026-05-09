import { createFileRoute } from "@tanstack/react-router";
import { PageHeader } from "@/components/page-header";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { useEngagements, useRequirements } from "@/lib/data-hooks";
import { Building2, Users } from "lucide-react";

export const Route = createFileRoute("/engagements")({
  head: () => ({
    meta: [
      { title: "Engagements — Cadence" },
      { name: "description", content: "All active monthly delivery engagements with vendors, capacity and approvers." },
    ],
  }),
  component: EngagementsPage,
});

const categoryLabels: Record<string, string> = {
  app_development: "App Development",
  analytics: "Analytics",
  staff_aug: "Staff Augmentation",
  middleware: "Middleware",
  api_integration: "API Integration",
  support: "Support",
  innovation: "Innovation / POC",
};

function EngagementsPage() {
  const { data: engagements = [] } = useEngagements();
  const { data: requirements = [] } = useRequirements();

  return (
    <div>
      <PageHeader
        title="Engagements"
        description="Each engagement runs on a fixed monthly commercial. Vendors should never sit idle — bandwidth is auto-filled from backlog."
      />
      <div className="grid gap-4 p-6 md:grid-cols-2 xl:grid-cols-3">
        {engagements.map((e) => {
          const items = requirements.filter((r) => r.engagement_id === e.id);
          const used = items
            .filter((r) => ["planned", "in_development", "uat", "signed_off"].includes(r.status))
            .reduce((s, r) => s + r.estimated_hours, 0);
          const pct = e.monthly_capacity_hours ? Math.round((used / e.monthly_capacity_hours) * 100) : 0;
          return (
            <Card key={e.id} className="overflow-hidden border-border/60 shadow-[var(--shadow-card)]">
              <div className="h-1.5 bg-[var(--gradient-accent)]" />
              <CardHeader className="pb-2">
                <div className="flex items-start justify-between gap-2">
                  <div>
                    <CardTitle className="text-base flex items-center gap-2">
                      <Building2 className="h-4 w-4 text-muted-foreground" />
                      {e.name}
                    </CardTitle>
                    <p className="text-xs text-muted-foreground mt-1">{e.vendor}</p>
                  </div>
                  <Badge variant="secondary" className="text-[10px]">{categoryLabels[e.category] ?? e.category}</Badge>
                </div>
              </CardHeader>
              <CardContent className="space-y-3">
                <div className="grid grid-cols-2 gap-3 text-xs">
                  <Field label="Monthly Capacity" value={`${e.monthly_capacity_hours} hrs`} />
                  <Field label="Utilization" value={`${pct}%`} />
                  <Field label="Approver" value={e.approver} />
                  <Field label="Biz Owner" value={e.business_owner} />
                </div>
                <div className="flex items-center justify-between border-t border-border/60 pt-3 text-xs text-muted-foreground">
                  <span className="flex items-center gap-1.5"><Users className="h-3.5 w-3.5" /> {items.length} requirements</span>
                  <span>{items.filter((r) => r.status === "in_development").length} in dev</span>
                </div>
              </CardContent>
            </Card>
          );
        })}
      </div>
    </div>
  );
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-[10px] uppercase tracking-wider text-muted-foreground">{label}</p>
      <p className="mt-0.5 text-sm font-medium">{value}</p>
    </div>
  );
}
