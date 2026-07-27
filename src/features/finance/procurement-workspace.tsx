import { Link } from "@tanstack/react-router";
import { ClipboardCheck, MessageSquareWarning, ShieldAlert, WalletCards } from "lucide-react";

import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  CursorPagination,
  EmptyFinanceState,
  FinanceBoundary,
  FinanceError,
  FinanceNav,
  PaymentTimeline,
  PermissionNotice,
  SourceFacts,
  VersionBanner,
} from "@/features/finance/components";
import type {
  InvoiceView,
  PaymentStatus,
  ProcurementDecision,
  ProcurementException,
} from "@/features/finance/contracts";
import {
  useControlTower,
  useFinanceInvoice,
  usePaymentUpdate,
  useProcurementExceptionApproval,
  useProcurementExceptionRequest,
  useProcurementQuery,
  useProcurementReview,
} from "@/features/finance/hooks";
import { useCursorPager } from "@/features/finance/pagination";
import {
  formatDateTime,
  invoiceCommandsDisabled,
  localDateTimeToIso,
  safeFinanceActionPath,
} from "@/features/finance/presentation";

export function ProcurementWorkspace({ invoiceId }: { invoiceId?: string }) {
  const towerPager = useCursorPager();
  const tower = useControlTower(towerPager.cursor);
  const invoice = useFinanceInvoice(invoiceId ?? "");

  return (
    <div>
      <PageHeader
        title="Procurement control tower"
        description="Server-scoped readiness matrix, immutable review decisions, assigned queries, authority-bound exceptions and AP status."
      >
        <FinanceNav />
      </PageHeader>
      <FinanceBoundary queries={[tower]}>
        {tower.data && (
          <div className="space-y-6 p-4 sm:p-6">
            <div
              className={`rounded-lg border p-3 text-sm ${
                tower.data.freshness === "STALE" ? "border-warning/40 bg-warning/5" : "bg-muted/30"
              }`}
              role={tower.data.freshness === "STALE" ? "alert" : "status"}
            >
              Matrix is {tower.data.freshness.toLowerCase()} · refreshed{" "}
              {formatDateTime(tower.data.refreshedAt)}. Live source state is never presented as a
              historical snapshot.
            </div>
            <PermissionNotice permissions={tower.data.permissions} required="PROCUREMENT_REVIEW">
              <>
                <ControlTowerTable rows={tower.data.rows.items} selectedInvoiceId={invoiceId} />
                <CursorPagination
                  label="Procurement control tower"
                  hasPrevious={towerPager.hasPrevious}
                  nextCursor={tower.data.rows.nextCursor}
                  onPrevious={towerPager.previous}
                  onNext={towerPager.next}
                />
              </>
            </PermissionNotice>
            {invoiceId && (
              <FinanceBoundary queries={[invoice]}>
                {invoice.data && <ProcurementInvoice invoice={invoice.data} />}
              </FinanceBoundary>
            )}
          </div>
        )}
      </FinanceBoundary>
    </div>
  );
}

function ControlTowerTable({
  rows,
  selectedInvoiceId,
}: {
  rows: NonNullable<ReturnType<typeof useControlTower>["data"]>["rows"]["items"];
  selectedInvoiceId?: string;
}) {
  if (rows.length === 0) {
    return (
      <EmptyFinanceState
        title="No Procurement work in scope"
        detail="The server returned no engagement-month review rows for this authority."
      />
    );
  }
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Readiness matrix</CardTitle>
      </CardHeader>
      <CardContent className="p-0">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[1180px] text-left text-xs">
            <caption className="sr-only">
              Procurement readiness by engagement month, including non-color text status
            </caption>
            <thead className="border-b bg-muted/40 text-muted-foreground">
              <tr>
                <th className="p-3">Engagement month</th>
                <th className="p-3">Invoice / queue</th>
                {["Roster", "Attendance", "Plan", "Linear", "Certification", "Confirmation", "Package", "Invoice", "Payment"].map(
                  (label) => <th key={label} className="p-3">{label}</th>,
                )}
                <th className="p-3">Action</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr
                  key={`${row.monthId}-${row.invoiceId ?? "none"}`}
                  className={row.invoiceId === selectedInvoiceId ? "border-b bg-muted/40" : "border-b"}
                >
                  <td className="p-3">
                    <span className="font-medium">{row.engagementLabel}</span>
                    <span className="block text-muted-foreground">{row.monthLabel}</span>
                  </td>
                  <td className="p-3">
                    {row.invoiceNumber ?? "No invoice"}
                    <span className="block text-muted-foreground">
                      {row.queue}{row.ageDays === null ? "" : ` · ${row.ageDays} days`}
                    </span>
                  </td>
                  {row.cells.map((cell) => {
                    const actionPath = safeFinanceActionPath(cell.actionPath);
                    return (
                      <td key={cell.key} className="p-3 align-top">
                        <StatusBadge status={cell.state} />
                        <span className="mt-1 block text-muted-foreground">
                          {cell.temporalMode.toLowerCase()} · {cell.freshness.toLowerCase()}
                        </span>
                        <span className="block">{cell.sourceLabel}</span>
                        {cell.version && <span className="block font-mono">v{cell.version}</span>}
                        {cell.ownerDisplay && <span className="block">Owner: {cell.ownerDisplay}</span>}
                        {actionPath ? (
                          <a className="mt-1 block font-medium text-primary hover:underline" href={actionPath}>
                            Open remediation
                          </a>
                        ) : cell.actionPath ? (
                          <span className="mt-1 block text-muted-foreground">
                            Remediation path unavailable
                          </span>
                        ) : null}
                      </td>
                    );
                  })}
                  <td className="p-3">
                    {row.invoiceId ? (
                      <Link
                        to="/finance/procurement"
                        search={{ invoiceId: row.invoiceId }}
                        className="font-medium text-primary hover:underline"
                      >
                        Review exact versions
                      </Link>
                    ) : (
                      <Link
                        to="/finance"
                        search={{ monthId: row.monthId, invoiceId: undefined, packageId: undefined }}
                        className="font-medium text-primary hover:underline"
                      >
                        Open owner workflow
                      </Link>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </CardContent>
    </Card>
  );
}

function ProcurementInvoice({ invoice }: { invoice: InvoiceView }) {
  const commandsBlocked = invoiceCommandsDisabled(invoice);
  return (
    <section className="space-y-4" aria-labelledby="procurement-invoice-title">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 id="procurement-invoice-title" className="text-xl font-semibold">
            Review {invoice.invoiceNumber}
          </h2>
          <p className="text-sm text-muted-foreground">
            {invoice.engagementLabel} · {invoice.monthLabel}
          </p>
        </div>
        <StatusBadge status={invoice.state} />
      </div>
      <VersionBanner
        version={invoice.version}
        stale={invoice.freshness === "STALE"}
        readOnly={invoice.readOnly}
        updatedAt={invoice.updatedAt}
      />
      {commandsBlocked && (
        <p className="rounded-lg border border-warning/40 bg-warning/5 p-3 text-sm" role="alert">
          This invoice is read-only, stale, or terminal. Refresh the current version or use the
          source owner workflow before recording any consequential action.
        </p>
      )}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Exact review source set</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <p className="text-sm">
            Invoice v{invoice.version} · package{" "}
            {invoice.linkedPackage ? `v${invoice.linkedPackage.version}` : "unavailable"} · readiness{" "}
            {invoice.readiness?.runId ?? "unavailable"}.
          </p>
          {invoice.readiness?.rules.map((rule) => (
            <div key={rule.ruleId} className="rounded-md border p-3">
              <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
                <span className="font-medium">{rule.pillar} · {rule.label}</span>
                <StatusBadge status={rule.status} />
              </div>
              {rule.source && <div className="mt-3"><SourceFacts source={rule.source} /></div>}
              {rule.status === "EXCEPTION_ACCEPTED_BY_PROCUREMENT" && (
                <p className="mt-2 text-xs font-medium">
                  Exception accepted; this source is not relabelled as confirmed or complete.
                </p>
              )}
            </div>
          ))}
        </CardContent>
      </Card>

      <div className="grid gap-4 xl:grid-cols-2">
        <ReviewForm invoice={invoice} />
        <QueryForm invoice={invoice} />
        <ExceptionForm invoice={invoice} />
        <PaymentForm invoice={invoice} />
      </div>

      <div className="grid gap-4 xl:grid-cols-2">
        <Card>
          <CardHeader><CardTitle className="text-base">Immutable review history</CardTitle></CardHeader>
          <CardContent>
            {invoice.reviews.length === 0 ? <EmptyFinanceState title="No review decisions" detail="A server-authorized Procurement decision will appear here." /> : (
              <ol className="space-y-3">{invoice.reviews.map((review) => <li key={review.reviewId} className="rounded-md border p-3 text-sm"><div className="flex items-center justify-between gap-2"><span>{review.actorDisplay} · {review.authorityDisplay}</span><StatusBadge status={review.decision} /></div><p className="mt-1 text-xs text-muted-foreground">invoice v{review.invoiceVersion} · package v{review.packageVersion} · readiness {review.readinessRunId} · {formatDateTime(review.recordedAt)}</p>{review.comment && <p className="mt-2">{review.comment}</p>}</li>)}</ol>
            )}
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle className="text-base">Queries and exception disclosure</CardTitle></CardHeader>
          <CardContent className="space-y-3">
            {invoice.queries.map((query) => <div key={query.queryId} className="rounded-md border p-3 text-sm"><div className="flex items-center justify-between gap-2"><span className="font-medium">{query.category} · {query.ownerDisplay}</span><StatusBadge status={query.status} /></div><p className="mt-1">{query.summary}</p><p className="mt-1 text-xs text-muted-foreground">Due {formatDateTime(query.dueAt)}. Upstream correction uses the owner workflow, never a Procurement edit.</p></div>)}
            {invoice.exceptions.map((exception) => (
              <ExceptionDisclosure
                key={exception.exceptionId}
                exception={exception}
                invoice={invoice}
              />
            ))}
            {invoice.queries.length === 0 && invoice.exceptions.length === 0 && <EmptyFinanceState title="No query or exception" detail="Exceptions remain version-bound and disclosed; they never alter upstream evidence." />}
          </CardContent>
        </Card>
        <PaymentTimeline
          permissions={invoice.permissions}
          events={invoice.paymentTimeline}
        />
      </div>
    </section>
  );
}

function ReviewForm({ invoice }: { invoice: InvoiceView }) {
  const mutation = useProcurementReview(invoice.invoiceId);
  const blocked = invoiceCommandsDisabled(invoice);
  if (!invoice.permissions.includes("PROCUREMENT_REVIEW")) return null;
  return (
    <ActionCard title="Record Procurement decision" icon={ClipboardCheck} error={mutation.error}>
      <form className="space-y-3" onSubmit={(event) => {
        event.preventDefault();
        if (blocked || !invoice.linkedPackage || !invoice.readiness) return;
        const form = new FormData(event.currentTarget);
        const decision = String(form.get("decision")) as Exclude<ProcurementDecision, "EXCEPTION_ACCEPTED">;
        const category = String(form.get("category") || "") || null;
        const comment = String(form.get("comment") || "") || null;
        if (decision !== "APPROVED_FOR_PROCESSING" && (!category || !comment)) return;
        mutation.mutate({ expectedVersion: invoice.version, decision, category, comment, packageId: invoice.linkedPackage.packageId, packageVersion: invoice.linkedPackage.version, readinessRunId: invoice.readiness.runId });
      }}>
        <fieldset disabled={blocked || mutation.isPending || !invoice.linkedPackage || !invoice.readiness} className="space-y-3">
          <div><Label htmlFor="review-decision">Decision</Label><select id="review-decision" name="decision" required className="h-9 w-full rounded-md border bg-background px-3 text-sm"><option value="APPROVED_FOR_PROCESSING">Approved for processing</option><option value="CHANGES_REQUESTED">Changes requested</option><option value="ON_HOLD">On hold</option><option value="REJECTED">Rejected</option></select></div>
          <div><Label htmlFor="review-category">Category (required for non-approval)</Label><Input id="review-category" name="category" /></div>
          <div><Label htmlFor="review-comment">Reason / comment (required for non-approval)</Label><Textarea id="review-comment" name="comment" /></div>
          <Confirmation version={invoice.version}>I confirm this immutable decision applies only to the exact invoice, package and readiness versions shown above.</Confirmation>
          <Button type="submit">Record decision</Button>
        </fieldset>
      </form>
    </ActionCard>
  );
}

function QueryForm({ invoice }: { invoice: InvoiceView }) {
  const mutation = useProcurementQuery(invoice.invoiceId);
  const blocked = invoiceCommandsDisabled(invoice);
  if (!invoice.permissions.includes("PROCUREMENT_QUERY")) return null;
  return (
    <ActionCard title="Create assigned query" icon={MessageSquareWarning} error={mutation.error}>
      <form className="space-y-3" onSubmit={(event) => {
        event.preventDefault();
        if (blocked) return;
        const form = new FormData(event.currentTarget);
        const dueAt = localDateTimeToIso(String(form.get("dueAt")));
        if (!dueAt) return;
        mutation.mutate({ expectedVersion: invoice.version, category: String(form.get("category")), summary: String(form.get("summary")), ownerId: String(form.get("ownerId")), dueAt, reason: String(form.get("reason")) });
      }}>
        <fieldset disabled={blocked || mutation.isPending} className="space-y-3">
          <div><Label htmlFor="query-category">Category</Label><Input id="query-category" name="category" required /></div>
          <div><Label htmlFor="query-owner">Responsible owner ID</Label><Input id="query-owner" name="ownerId" required /></div>
          <div><Label htmlFor="query-due">Due at</Label><Input id="query-due" name="dueAt" type="datetime-local" required /></div>
          <div><Label htmlFor="query-summary">Requested change</Label><Textarea id="query-summary" name="summary" required /></div>
          <div><Label htmlFor="query-reason">Reason</Label><Input id="query-reason" name="reason" required /></div>
          <Confirmation version={invoice.version}>I understand source correction is assigned to its owner workflow and creates new invoice/package lineage.</Confirmation>
          <Button type="submit">Create query</Button>
        </fieldset>
      </form>
    </ActionCard>
  );
}

function ExceptionForm({ invoice }: { invoice: InvoiceView }) {
  const mutation = useProcurementExceptionRequest(invoice.invoiceId);
  const blocked = invoiceCommandsDisabled(invoice);
  const failedRules = invoice.readiness?.rules.filter((rule) => rule.status.startsWith("BLOCKED_")) ?? [];
  if (!invoice.permissions.includes("PROCUREMENT_EXCEPTION")) return null;
  return (
    <ActionCard title="Request authority-bound exception" icon={ShieldAlert} error={mutation.error}>
      <form className="space-y-3" onSubmit={(event) => {
        event.preventDefault();
        if (blocked || !invoice.linkedPackage || !invoice.readiness) return;
        const form = new FormData(event.currentTarget);
        const validUntil = localDateTimeToIso(String(form.get("validUntil")));
        if (!validUntil) return;
        mutation.mutate({ expectedVersion: invoice.version, ruleId: String(form.get("ruleId")), readinessRunId: invoice.readiness.runId, packageId: invoice.linkedPackage.packageId, packageVersion: invoice.linkedPackage.version, rationale: String(form.get("rationale")), validUntil });
      }}>
        <fieldset disabled={blocked || mutation.isPending || failedRules.length === 0 || !invoice.linkedPackage || !invoice.readiness} className="space-y-3">
          <div><Label htmlFor="exception-rule">Exact failed rule</Label><select id="exception-rule" name="ruleId" required className="h-9 w-full rounded-md border bg-background px-3 text-sm"><option value="">Select blocked rule</option>{failedRules.map((rule) => <option key={rule.ruleId} value={rule.ruleId}>{rule.pillar} · {rule.label}</option>)}</select></div>
          <div><Label htmlFor="exception-rationale">Rationale</Label><Textarea id="exception-rationale" name="rationale" required /></div>
          <div><Label htmlFor="exception-expiry">Valid until</Label><Input id="exception-expiry" name="validUntil" type="datetime-local" required /></div>
          <p className="rounded-md bg-muted/40 p-3 text-sm">
            When policy requires two people, this records a pending request only. A different
            authenticated Procurement authority must approve it from this workspace; an approver
            identity cannot be nominated here.
          </p>
          <Confirmation version={invoice.version}>I confirm this request remains disclosed, expires as entered, and does not relabel confirmation or source evidence.</Confirmation>
          <Button type="submit">Request exception</Button>
        </fieldset>
      </form>
    </ActionCard>
  );
}

function ExceptionDisclosure({
  exception,
  invoice,
}: {
  exception: ProcurementException;
  invoice: InvoiceView;
}) {
  const approval = useProcurementExceptionApproval(
    invoice.invoiceId,
    exception.exceptionId,
  );
  const pending = exception.status === "PENDING_SECOND_APPROVAL";
  const canApprove = invoice.permissions.includes("PROCUREMENT_EXCEPTION");
  const blocked = invoiceCommandsDisabled(invoice);

  return (
    <article className="rounded-md border border-warning/40 p-3 text-sm">
      <div className="flex items-center justify-between gap-2">
        <span className="font-medium">Rule {exception.ruleId}</span>
        <StatusBadge status={exception.status} />
      </div>
      <p className="mt-1">{exception.rationale}</p>
      <p className="mt-1 text-xs text-muted-foreground">
        requested by {exception.requestedByDisplay} · invoice v{exception.invoiceVersion} ·
        package v{exception.packageVersion} · policy v{exception.policyVersion} · valid until{" "}
        {formatDateTime(exception.validUntil)}
      </p>
      {exception.secondApproverDisplay && (
        <p className="mt-1 text-xs">
          Approved by {exception.secondApproverDisplay} at{" "}
          {formatDateTime(exception.secondApprovedAt)}.
        </p>
      )}
      {exception.expiredAt && (
        <p className="mt-1 text-xs" role="status">
          Expired at {formatDateTime(exception.expiredAt)}; approval is no longer available.
        </p>
      )}
      {pending && canApprove && (
        <form
          className="mt-3 space-y-2 rounded-md border bg-muted/20 p-3"
          onSubmit={(event) => {
            event.preventDefault();
            if (blocked) return;
            approval.mutate({
              expectedVersion: invoice.version,
              invoiceId: invoice.invoiceId,
              ruleId: exception.ruleId,
              readinessRunId: exception.readinessRunId,
              packageId: exception.packageId,
              packageVersion: exception.packageVersion,
              policyVersionId: exception.policyVersionId,
              policyVersion: exception.policyVersion,
            });
          }}
        >
          <p>
            Pending distinct approval. This action uses the current authenticated Procurement
            actor; the server rejects the original requester and any stale or mismatched lineage.
          </p>
          <FinanceError error={approval.error} compact />
          <fieldset disabled={blocked || approval.isPending}>
            <Confirmation version={invoice.version}>
              I am a distinct authorized reviewer and approve the exact exception lineage shown.
            </Confirmation>
            <Button className="mt-2" type="submit">
              Approve as current signed-in reviewer
            </Button>
          </fieldset>
        </form>
      )}
    </article>
  );
}

const paymentStatuses: PaymentStatus[] = ["NOT_SUBMITTED", "SUBMITTED_TO_AP", "VALIDATION_IN_PROGRESS", "PAYMENT_SCHEDULED", "PAYMENT_INITIATED", "PAID", "PAYMENT_FAILED", "ON_HOLD"];

function PaymentForm({ invoice }: { invoice: InvoiceView }) {
  const mutation = usePaymentUpdate(invoice.invoiceId);
  const blocked = invoiceCommandsDisabled(invoice);
  if (!invoice.permissions.includes("PAYMENT_UPDATE")) return null;
  return (
    <ActionCard title="Append payment status" icon={WalletCards} error={mutation.error}>
      <form className="space-y-3" onSubmit={(event) => {
        event.preventDefault();
        if (blocked) return;
        const form = new FormData(event.currentTarget);
        const statusAt = localDateTimeToIso(String(form.get("statusAt")));
        if (!statusAt) return;
        mutation.mutate({ expectedVersion: invoice.version, status: String(form.get("status")) as PaymentStatus, statusAt, expectedPaymentDate: String(form.get("expectedPaymentDate") || "") || null, actualPaymentDate: String(form.get("actualPaymentDate") || "") || null, externalReference: String(form.get("externalReference") || "") || null, comment: String(form.get("comment")) });
      }}>
        <fieldset disabled={blocked || mutation.isPending} className="space-y-3">
          <div><Label htmlFor="payment-status">Status</Label><select id="payment-status" name="status" required className="h-9 w-full rounded-md border bg-background px-3 text-sm">{paymentStatuses.map((status) => <option key={status} value={status}>{status.replaceAll("_", " ").toLowerCase()}</option>)}</select></div>
          <div><Label htmlFor="payment-at">Status timestamp</Label><Input id="payment-at" name="statusAt" type="datetime-local" required /></div>
          <div className="grid gap-3 sm:grid-cols-2"><div><Label htmlFor="expected-payment">Expected date</Label><Input id="expected-payment" name="expectedPaymentDate" type="date" /></div><div><Label htmlFor="actual-payment">Actual date</Label><Input id="actual-payment" name="actualPaymentDate" type="date" /></div></div>
          <div><Label htmlFor="external-reference">AP / ERP reference</Label><Input id="external-reference" name="externalReference" /></div>
          <div><Label htmlFor="payment-comment">Sanitized comment</Label><Textarea id="payment-comment" name="comment" required /></div>
          <Confirmation version={invoice.version}>I confirm this appends status history only. It does not transfer funds or mutate evidence/readiness.</Confirmation>
          <Button type="submit">Append status</Button>
        </fieldset>
      </form>
    </ActionCard>
  );
}

function ActionCard({
  title,
  icon: Icon,
  error,
  children,
}: {
  title: string;
  icon: typeof ClipboardCheck;
  error: Error | null;
  children: React.ReactNode;
}) {
  return (
    <Card>
      <CardHeader><CardTitle className="flex items-center gap-2 text-base"><Icon className="h-4 w-4" aria-hidden="true" />{title}</CardTitle></CardHeader>
      <CardContent><FinanceError error={error} compact />{children}</CardContent>
    </Card>
  );
}

function Confirmation({ version, children }: { version: number; children: React.ReactNode }) {
  return (
    <label className="flex items-start gap-2 text-sm">
      <input className="mt-1" type="checkbox" name="confirmed" required />
      <span>{children} Expected server version: v{version}.</span>
    </label>
  );
}
