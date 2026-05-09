import { createFileRoute } from "@tanstack/react-router";
import { useMemo, useState } from "react";
import { PageHeader } from "@/components/page-header";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from "@/components/ui/select";
import { StatusBadge, PriorityBadge } from "@/components/status-badge";
import { useEngagements, useRequirements } from "@/lib/data-hooks";
import { Plus, Search, AlertCircle, CheckCircle2 } from "lucide-react";
import {
  Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle, SheetTrigger,
} from "@/components/ui/sheet";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { supabase } from "@/integrations/supabase/client";
import { toast } from "sonner";
import { useQueryClient } from "@tanstack/react-query";

export const Route = createFileRoute("/requirements")({
  head: () => ({
    meta: [
      { title: "Requirements — Cadence" },
      { name: "description", content: "Capture, prioritize and govern monthly requirements with mandatory acceptance criteria and UAT cases." },
    ],
  }),
  component: RequirementsPage,
});

function RequirementsPage() {
  const { data: requirements = [] } = useRequirements();
  const { data: engagements = [] } = useEngagements();
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
        title="Requirements Intake"
        description="Items cannot move to estimation unless acceptance criteria, UAT cases, owner and priority are filled in."
      >
        <NewRequirementSheet engagements={engagements} />
      </PageHeader>

      <div className="space-y-4 p-6">
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
    </div>
  );
}

function NewRequirementSheet({ engagements }: { engagements: any[] }) {
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({
    title: "", description: "", module: "", engagement_id: "",
    business_owner: "", priority: "p3", acceptance_criteria: "", uat_cases: "",
  });

  const submit = async () => {
    if (!form.title || !form.engagement_id) {
      toast.error("Title and engagement are required");
      return;
    }
    const { error } = await supabase.from("requirements").insert({
      ...form,
      status: form.acceptance_criteria && form.uat_cases ? "submitted" : "draft",
    } as any);
    if (error) { toast.error(error.message); return; }
    toast.success("Requirement created");
    qc.invalidateQueries({ queryKey: ["requirements"] });
    setOpen(false);
    setForm({ title: "", description: "", module: "", engagement_id: "", business_owner: "", priority: "p3", acceptance_criteria: "", uat_cases: "" });
  };

  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetTrigger asChild>
        <Button size="sm" className="gap-1.5"><Plus className="h-4 w-4" /> New Requirement</Button>
      </SheetTrigger>
      <SheetContent className="w-full sm:max-w-lg overflow-y-auto">
        <SheetHeader>
          <SheetTitle>New requirement</SheetTitle>
          <SheetDescription>Items with acceptance criteria + UAT cases auto-promote to <em>Submitted</em>.</SheetDescription>
        </SheetHeader>
        <div className="mt-6 space-y-4 px-4">
          <Field label="Title">
            <Input value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} placeholder="e.g. Offline cart sync" />
          </Field>
          <Field label="Description">
            <Textarea value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} rows={3} />
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Engagement">
              <Select value={form.engagement_id} onValueChange={(v) => setForm({ ...form, engagement_id: v })}>
                <SelectTrigger><SelectValue placeholder="Select…" /></SelectTrigger>
                <SelectContent>
                  {engagements.map((e) => <SelectItem key={e.id} value={e.id}>{e.name}</SelectItem>)}
                </SelectContent>
              </Select>
            </Field>
            <Field label="Priority">
              <Select value={form.priority} onValueChange={(v) => setForm({ ...form, priority: v })}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  {["p1","p2","p3","p4"].map((p) => <SelectItem key={p} value={p}>{p.toUpperCase()}</SelectItem>)}
                </SelectContent>
              </Select>
            </Field>
            <Field label="Module">
              <Input value={form.module} onChange={(e) => setForm({ ...form, module: e.target.value })} />
            </Field>
            <Field label="Business owner">
              <Input value={form.business_owner} onChange={(e) => setForm({ ...form, business_owner: e.target.value })} />
            </Field>
          </div>
          <Field label="Acceptance criteria *">
            <Textarea value={form.acceptance_criteria} onChange={(e) => setForm({ ...form, acceptance_criteria: e.target.value })} rows={2} />
          </Field>
          <Field label="UAT cases *">
            <Textarea value={form.uat_cases} onChange={(e) => setForm({ ...form, uat_cases: e.target.value })} rows={2} />
          </Field>
          <Button onClick={submit} className="w-full">Create requirement</Button>
        </div>
      </SheetContent>
    </Sheet>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1.5">
      <Label className="text-xs uppercase tracking-wider text-muted-foreground">{label}</Label>
      {children}
    </div>
  );
}
