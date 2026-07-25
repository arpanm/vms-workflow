import { createFileRoute } from "@tanstack/react-router";
import { useMemo, useState } from "react";
import { PageHeader } from "@/components/page-header";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from "@/components/ui/select";
import { StatusBadge, PriorityBadge } from "@/components/status-badge";
import {
  useEngagements,
  useRequirements,
  type Requirement,
} from "@/lib/data-hooks";
import { Search, AlertCircle, CheckCircle2 } from "lucide-react";
import { requireLegacyRoute } from "@/lib/legacy-route";
import { QueryState } from "@/components/query-state";

const EMPTY_REQUIREMENTS: Requirement[] = [];

export const Route = createFileRoute("/requirements")({
  beforeLoad: requireLegacyRoute,
  head: () => ({
    meta: [
      { title: "Requirements — Cadence" },
      { name: "description", content: "Read-only compatibility view for legacy fixed-cost requirements." },
    ],
  }),
  component: RequirementsPage,
});

function RequirementsPage() {
  const requirementsQuery = useRequirements();
  const engagementsQuery = useEngagements();
  const requirements = requirementsQuery.data ?? EMPTY_REQUIREMENTS;
  const engagements = engagementsQuery.data ?? [];
  const [q, setQ] = useState("");
  const [status, setStatus] = useState<string>("all");
  const [eng, setEng] = useState<string>("all");

  const filtered = useMemo(
    () =>
      requirements.filter(
        (r) =>
          (status === "all" || r.status === status) &&
          (eng === "all" || r.engagement_id === eng) &&
          (q === "" || r.title.toLowerCase().includes(q.toLowerCase())),
      ),
    [requirements, q, status, eng],
  );

  return (
    <div>
      <PageHeader
        title="Legacy Requirements"
        description="Read-only compatibility records retained during migration to canonical monthly plans and deliverables."
      />

      <QueryState queries={[requirementsQuery, engagementsQuery]}>
      <div className="space-y-4 p-6">
        <Card className="border-info/30 bg-info/10">
          <CardContent className="py-4 text-sm text-info-foreground">
            This compatibility view cannot create or change requirements. Use
            the canonical delivery-planning workflow when that feature is
            enabled.
          </CardContent>
        </Card>
        <div className="flex flex-wrap items-center gap-2">
          <div className="relative max-w-sm flex-1">
            <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
            <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search requirements…" className="pl-8" />
          </div>
          <Select value={eng} onValueChange={setEng}>
            <SelectTrigger className="w-[200px]"><SelectValue placeholder="Engagement" /></SelectTrigger>
            <SelectContent>
              <SelectItem value="all">All engagements</SelectItem>
              {engagements.map((e) => <SelectItem key={e.id} value={e.id}>{e.name}</SelectItem>)}
            </SelectContent>
          </Select>
          <Select value={status} onValueChange={setStatus}>
            <SelectTrigger className="w-[180px]"><SelectValue placeholder="Status" /></SelectTrigger>
            <SelectContent>
              {["all","draft","submitted","estimated","approved","planned","in_development","uat","signed_off"].map((s) => (
                <SelectItem key={s} value={s}>{s.replace(/_/g," ")}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <Card className="border-border/60 shadow-[var(--shadow-card)]">
          <CardContent className="p-0">
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="bg-muted/50 text-left text-xs uppercase tracking-wider text-muted-foreground">
                  <tr>
                    <th className="px-4 py-3">Title</th>
                    <th className="px-4 py-3">Engagement</th>
                    <th className="px-4 py-3">Module</th>
                    <th className="px-4 py-3">Priority</th>
                    <th className="px-4 py-3 text-right">Hours</th>
                    <th className="px-4 py-3">Owner</th>
                    <th className="px-4 py-3">Status</th>
                    <th className="px-4 py-3">Quality</th>
                  </tr>
                </thead>
                <tbody>
                  {filtered.map((r) => {
                    const e = engagements.find((x) => x.id === r.engagement_id);
                    const complete = !!r.acceptance_criteria && !!r.uat_cases && !!r.business_owner;
                    return (
                      <tr key={r.id} className="border-t border-border/60 hover:bg-muted/30">
                        <td className="px-4 py-3">
                          <div className="font-medium">{r.title}</div>
                          <div className="text-xs text-muted-foreground line-clamp-1">{r.description}</div>
                        </td>
                        <td className="px-4 py-3 text-muted-foreground">{e?.name ?? "—"}</td>
                        <td className="px-4 py-3 text-muted-foreground">{r.module}</td>
                        <td className="px-4 py-3"><PriorityBadge priority={r.priority} /></td>
                        <td className="px-4 py-3 text-right tabular-nums">{r.estimated_hours || "—"}</td>
                        <td className="px-4 py-3 text-muted-foreground">{r.business_owner}</td>
                        <td className="px-4 py-3"><StatusBadge status={r.status} /></td>
                        <td className="px-4 py-3">
                          {complete ? (
                            <span className="inline-flex items-center gap-1 text-xs text-success-foreground">
                              <CheckCircle2 className="h-3.5 w-3.5 text-success" /> Ready
                            </span>
                          ) : (
                            <span className="inline-flex items-center gap-1 text-xs text-warning-foreground">
                              <AlertCircle className="h-3.5 w-3.5 text-warning" /> Missing fields
                            </span>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                  {filtered.length === 0 && (
                    <tr><td colSpan={8} className="px-4 py-12 text-center text-sm text-muted-foreground">No requirements match your filters.</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          </CardContent>
        </Card>
      </div>
      </QueryState>
    </div>
  );
}
