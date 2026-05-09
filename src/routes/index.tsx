import { createFileRoute } from "@tanstack/react-router";
import { useMemo } from "react";
import {
  Activity, AlertTriangle, CheckCircle2, Clock, FileText, Receipt, TrendingUp, Users,
} from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Progress } from "@/components/ui/progress";
import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import {
  useApprovals, useEngagements, useInvoices, useRequirements, useUat,
} from "@/lib/data-hooks";
import { useRole } from "@/lib/use-role";
import { ROLES } from "@/lib/role-store";
import {
  Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis, Cell,
} from "recharts";

export const Route = createFileRoute("/")({
  head: () => ({
    meta: [
      { title: "Executive Dashboard — Cadence" },
      { name: "description", content: "Monthly delivery health, vendor utilization, UAT aging and invoice status at a glance." },
    ],
  }),
  component: Dashboard,
});

function KPI({
  icon: Icon, label, value, sub, accent,
}: { icon: any; label: string; value: string; sub?: string; accent?: string }) {
  return (
    <Card className="overflow-hidden border-border/60 shadow-[var(--shadow-card)]">
      <CardContent className="p-5">
        <div className="flex items-start justify-between">
          <div>
            <p className="text-xs font-medium uppercase tracking-wider text-muted-foreground">{label}</p>
            <p className="mt-2 text-2xl font-semibold tracking-tight">{value}</p>
            {sub && <p className="mt-1 text-xs text-muted-foreground">{sub}</p>}
          </div>
          <div className={`grid h-10 w-10 place-items-center rounded-lg ${accent ?? "bg-primary/10 text-primary"}`}>
            <Icon className="h-5 w-5" />
          </div>
        </div>
      </CardContent>
    </Card>
  );
}

function Dashboard() {
  const [role] = useRole();
  const persona = ROLES.find((r) => r.id === role)!;
  const { data: engagements = [] } = useEngagements();
  const { data: requirements = [] } = useRequirements();
  const { data: approvals = [] } = useApprovals();
  const { data: uat = [] } = useUat();
  const { data: invoices = [] } = useInvoices();

  const stats = useMemo(() => {
    const totalCapacity = engagements.reduce((s, e) => s + e.monthly_capacity_hours, 0);
    const planned = requirements
      .filter((r) => ["planned", "in_development", "uat", "signed_off"].includes(r.status))
      .reduce((s, r) => s + r.estimated_hours, 0);
    const utilization = totalCapacity ? Math.round((planned / totalCapacity) * 100) : 0;
    const pendingApprovals = approvals.filter((a) => a.status === "pending").length;
    const uatAging = uat.filter((u) => u.status === "in_progress").length;
    const inDev = requirements.filter((r) => r.status === "in_development").length;
    const signedOff = requirements.filter((r) => r.status === "signed_off").length;
    const invoicesPending = invoices.filter((i) => !["paid"].includes(i.status)).length;
    const invoicesValue = invoices.reduce((s, i) => s + Number(i.amount), 0);

    return { totalCapacity, planned, utilization, pendingApprovals, uatAging, inDev, signedOff, invoicesPending, invoicesValue };
  }, [engagements, requirements, approvals, uat, invoices]);

  const utilByEngagement = useMemo(
    () =>
      engagements.map((e) => {
        const used = requirements
          .filter((r) => r.engagement_id === e.id && ["planned", "in_development", "uat", "signed_off"].includes(r.status))
          .reduce((s, r) => s + r.estimated_hours, 0);
        return {
          name: e.name.replace(" App", "").replace(" Middleware", " MW"),
          used,
          capacity: e.monthly_capacity_hours,
          pct: e.monthly_capacity_hours ? Math.round((used / e.monthly_capacity_hours) * 100) : 0,
        };
      }),
    [engagements, requirements],
  );

  return (
    <div>
      <PageHeader
        title="Executive Dashboard"
        description={`Real-time governance health for ${persona.label}. Monthly cycle: requirement freeze → estimation → approval → sprint → UAT → invoice.`}
      />

      <div className="space-y-6 p-6">
        {/* KPIs */}
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
          <KPI icon={TrendingUp} label="Vendor Utilization" value={`${stats.utilization}%`}
               sub={`${stats.planned.toLocaleString()} / ${stats.totalCapacity.toLocaleString()} hrs`}
               accent="bg-accent/15 text-accent" />
          <KPI icon={Clock} label="Pending Approvals" value={String(stats.pendingApprovals)}
               sub="SLA: 48h then auto-approve"
               accent="bg-warning/15 text-warning-foreground" />
          <KPI icon={AlertTriangle} label="UAT In-Flight" value={String(stats.uatAging)}
               sub={`${stats.signedOff} signed off this cycle`}
               accent="bg-info/15 text-info-foreground" />
          <KPI icon={Receipt} label="Invoices In Flight" value={String(stats.invoicesPending)}
               sub={`₹${(stats.invoicesValue / 1e5).toFixed(1)}L total`}
               accent="bg-primary/10 text-primary" />
        </div>

        {/* Vendor utilization chart */}
        <div className="grid gap-6 lg:grid-cols-3">
          <Card className="lg:col-span-2 border-border/60 shadow-[var(--shadow-card)]">
            <CardHeader className="pb-2">
              <div className="flex items-start justify-between">
                <div>
                  <CardTitle className="text-base">Vendor Bandwidth Utilization</CardTitle>
                  <p className="text-xs text-muted-foreground mt-1">
                    Hours allocated for the current monthly cycle vs. committed capacity.
                  </p>
                </div>
                <Activity className="h-4 w-4 text-muted-foreground" />
              </div>
            </CardHeader>
            <CardContent>
              <div className="h-[280px]">
                <ResponsiveContainer>
                  <BarChart data={utilByEngagement} margin={{ top: 8, right: 12, left: -16, bottom: 0 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="oklch(0.92 0.01 240)" vertical={false} />
                    <XAxis dataKey="name" tick={{ fontSize: 12, fill: "oklch(0.5 0.02 250)" }} axisLine={false} tickLine={false} />
                    <YAxis tick={{ fontSize: 12, fill: "oklch(0.5 0.02 250)" }} axisLine={false} tickLine={false} />
                    <Tooltip
                      contentStyle={{ borderRadius: 8, border: "1px solid oklch(0.92 0.01 240)", boxShadow: "var(--shadow-card)" }}
                    />
                    <Bar dataKey="capacity" radius={[6, 6, 0, 0]} fill="oklch(0.92 0.01 240)" />
                    <Bar dataKey="used" radius={[6, 6, 0, 0]}>
                      {utilByEngagement.map((d, i) => (
                        <Cell key={i} fill={d.pct > 90 ? "oklch(0.62 0.22 25)" : d.pct > 60 ? "oklch(0.78 0.16 75)" : "oklch(0.72 0.16 165)"} />
                      ))}
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </CardContent>
          </Card>

          <Card className="border-border/60 shadow-[var(--shadow-card)]">
            <CardHeader className="pb-2">
              <CardTitle className="text-base">Engagement Health</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              {utilByEngagement.map((e) => (
                <div key={e.name}>
                  <div className="flex items-center justify-between text-xs">
                    <span className="font-medium">{e.name}</span>
                    <span className="text-muted-foreground">{e.pct}%</span>
                  </div>
                  <Progress value={e.pct} className="mt-1.5 h-1.5" />
                  <p className="mt-1 text-[11px] text-muted-foreground">
                    {e.used} / {e.capacity} hrs
                  </p>
                </div>
              ))}
            </CardContent>
          </Card>
        </div>

        {/* Recent activity / UAT aging */}
        <div className="grid gap-6 lg:grid-cols-2">
          <Card className="border-border/60 shadow-[var(--shadow-card)]">
            <CardHeader className="pb-2">
              <CardTitle className="text-base flex items-center gap-2">
                <FileText className="h-4 w-4 text-muted-foreground" /> Latest Requirements
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              {requirements.slice(0, 6).map((r) => (
                <div key={r.id} className="flex items-start justify-between gap-3 rounded-md border border-border/60 bg-muted/30 p-3">
                  <div className="min-w-0">
                    <p className="text-sm font-medium truncate">{r.title}</p>
                    <p className="text-xs text-muted-foreground truncate">{r.module} · {r.business_owner}</p>
                  </div>
                  <StatusBadge status={r.status} />
                </div>
              ))}
            </CardContent>
          </Card>

          <Card className="border-border/60 shadow-[var(--shadow-card)]">
            <CardHeader className="pb-2">
              <CardTitle className="text-base flex items-center gap-2">
                <CheckCircle2 className="h-4 w-4 text-muted-foreground" /> Governance Signals
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <Signal label="Requirement freeze adherence" value="96%" tone="success" />
              <Signal label="Auto-approved (SLA breach)" value={`${approvals.filter(a => a.status === "auto_approved").length} this cycle`} tone="info" />
              <Signal label="UAT aging > 4 days" value="2 items" tone="warning" />
              <Signal label="Invoices blocked > 7 days" value="1 item" tone="warning" />
              <Signal label="Active escalations" value="0" tone="success" />
            </CardContent>
          </Card>
        </div>

        <p className="text-xs text-muted-foreground flex items-center gap-1.5">
          <Users className="h-3.5 w-3.5" /> View tailored to <span className="font-medium text-foreground">{persona.label}</span>. Use the role switcher (top right) to see other personas.
        </p>
      </div>
    </div>
  );
}

function Signal({ label, value, tone }: { label: string; value: string; tone: "success" | "warning" | "info" | "destructive" }) {
  const dot = {
    success: "bg-success", warning: "bg-warning", info: "bg-info", destructive: "bg-destructive",
  }[tone];
  return (
    <div className="flex items-center justify-between rounded-md border border-border/60 bg-muted/30 px-3 py-2">
      <div className="flex items-center gap-2">
        <span className={`h-2 w-2 rounded-full ${dot}`} />
        <span className="text-sm">{label}</span>
      </div>
      <span className="text-sm font-medium">{value}</span>
    </div>
  );
}
