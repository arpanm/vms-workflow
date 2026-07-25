import { createFileRoute } from "@tanstack/react-router";
import { PageHeader } from "@/components/page-header";
import { Card, CardContent } from "@/components/ui/card";
import { StatusBadge } from "@/components/status-badge";
import { useEngagements, useInvoices } from "@/lib/data-hooks";
import { format, parseISO } from "date-fns";
import { requireLegacyRoute } from "@/lib/legacy-route";
import { QueryState } from "@/components/query-state";

export const Route = createFileRoute("/invoices")({
  beforeLoad: requireLegacyRoute,
  head: () => ({
    meta: [
      { title: "Invoices — Cadence" },
      { name: "description", content: "Vendor invoice queue with technical, finance and payment statuses." },
    ],
  }),
  component: InvoicesPage,
});

function InvoicesPage() {
  const invoicesQuery = useInvoices();
  const engagementsQuery = useEngagements();
  const invoices = invoicesQuery.data ?? [];
  const engagements = engagementsQuery.data ?? [];

  const totals = {
    in_flight: invoices.filter((i) => i.status !== "paid").reduce((s, i) => s + Number(i.amount), 0),
    paid: invoices.filter((i) => i.status === "paid").reduce((s, i) => s + Number(i.amount), 0),
  };

  const inr = (n: number) => `₹${(n / 1e5).toFixed(2)}L`;

  return (
    <div>
      <PageHeader
        title="Invoices"
        description="Legacy invoice records with explicit technical, finance and payment states."
      />
      <QueryState queries={[invoicesQuery, engagementsQuery]}>
      <div className="space-y-6 p-6">
        <div className="grid gap-4 sm:grid-cols-3">
          <Stat label="In flight" value={inr(totals.in_flight)} sub={`${invoices.filter(i => i.status !== "paid").length} invoices`} />
          <Stat label="Paid this cycle" value={inr(totals.paid)} sub={`${invoices.filter(i => i.status === "paid").length} invoices`} />
          <Stat label="Avg payment SLA" value="22 days" sub="Target ≤ 30 days" />
        </div>

        <Card className="border-border/60 shadow-[var(--shadow-card)]">
          <CardContent className="p-0">
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="bg-muted/50 text-left text-xs uppercase tracking-wider text-muted-foreground">
                  <tr>
                    <th className="px-4 py-3">Invoice</th>
                    <th className="px-4 py-3">Engagement</th>
                    <th className="px-4 py-3">Period</th>
                    <th className="px-4 py-3 text-right">Amount</th>
                    <th className="px-4 py-3">Uploaded</th>
                    <th className="px-4 py-3">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {invoices.map((i) => {
                    const e = engagements.find((x) => x.id === i.engagement_id);
                    return (
                      <tr key={i.id} className="border-t border-border/60 hover:bg-muted/30">
                        <td className="px-4 py-3 font-mono text-xs">{i.invoice_number}</td>
                        <td className="px-4 py-3">{e?.name ?? "—"}</td>
                        <td className="px-4 py-3 text-muted-foreground">{format(parseISO(i.period_month), "MMM yyyy")}</td>
                        <td className="px-4 py-3 text-right tabular-nums">₹{Number(i.amount).toLocaleString("en-IN")}</td>
                        <td className="px-4 py-3 text-muted-foreground text-xs">{format(parseISO(i.uploaded_at), "dd MMM")}</td>
                        <td className="px-4 py-3"><StatusBadge status={i.status} /></td>
                      </tr>
                    );
                  })}
                  {invoices.length === 0 && (
                    <tr><td colSpan={6} className="px-4 py-12 text-center text-muted-foreground">No invoices yet.</td></tr>
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

function Stat({ label, value, sub }: { label: string; value: string; sub: string }) {
  return (
    <Card className="border-border/60 shadow-[var(--shadow-card)]">
      <CardContent className="p-5">
        <p className="text-xs uppercase tracking-wider text-muted-foreground">{label}</p>
        <p className="mt-2 text-2xl font-semibold">{value}</p>
        <p className="mt-1 text-xs text-muted-foreground">{sub}</p>
      </CardContent>
    </Card>
  );
}
