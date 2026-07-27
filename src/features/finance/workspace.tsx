import { Link } from "@tanstack/react-router";
import { Download, FileCheck2, PackagePlus, RefreshCcw, Send, Upload } from "lucide-react";
import { useRef, useState } from "react";

import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Progress } from "@/components/ui/progress";
import { Textarea } from "@/components/ui/textarea";
import {
  ConfigurationCard,
  CursorPagination,
  DashboardQueues,
  EmptyFinanceState,
  Fact,
  FinanceBoundary,
  FinanceError,
  FinanceNav,
  PaymentTimeline,
  PermissionNotice,
  ReadinessChecklist,
  SourceFacts,
  VersionBanner,
} from "@/features/finance/components";
import type {
  CreateInvoiceInput,
  FinancePermission,
  InvoiceRepresentedMetadata,
  InvoiceView,
  UploadInvoiceDocumentInput,
} from "@/features/finance/contracts";
import {
  useCreateInvoice,
  useCreatePackageShare,
  useEvaluateReadiness,
  useFinanceAccess,
  useFinanceDashboard,
  useFinanceInvoice,
  useFinanceInvoices,
  useFinanceMonth,
  useFinanceMonths,
  useGeneratePackage,
  usePackage,
  usePackageAccess,
  usePackageDownload,
  usePackageDiff,
  usePackageHistory,
  usePackageShares,
  useRevokePackageShare,
  useSubmitInvoice,
  useUploadInvoiceDocument,
} from "@/features/finance/hooks";
import { useCursorPager } from "@/features/finance/pagination";
import {
  formatDateTime,
  invoiceCommandsDisabled,
  localDateTimeToIso,
  monthCommandsDisabled,
  shortenHash,
} from "@/features/finance/presentation";

export type FinanceWorkspaceSearch = {
  monthId?: string;
  invoiceId?: string;
  packageId?: string;
};

export function FinanceWorkspace({ search }: { search: FinanceWorkspaceSearch }) {
  const access = useFinanceAccess();
  const monthPager = useCursorPager();
  const invoicePager = useCursorPager();
  const months = useFinanceMonths(monthPager.cursor);
  const invoices = useFinanceInvoices(invoicePager.cursor);
  const dashboard = useFinanceDashboard();

  return (
    <div>
      <PageHeader
        title="Finance evidence workspace"
        description="Invoice metadata and immutable evidence versions. Readiness, Procurement exception and confirmed evidence remain distinct."
      >
        <FinanceNav />
      </PageHeader>
      <FinanceBoundary queries={[access, months, invoices, dashboard]}>
        {access.data && months.data && invoices.data && dashboard.data && (
          <div className="space-y-6 p-4 sm:p-6">
            <section aria-labelledby="integration-state-title">
              <h2 id="integration-state-title" className="sr-only">
                Controlled provider configuration
              </h2>
              <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                <ConfigurationCard label="Private storage" status={access.data.storage} />
                <ConfigurationCard label="Malware scanner" status={access.data.scanner} />
                <ConfigurationCard label="Package renderer" status={access.data.renderer} />
                <ConfigurationCard label="AP / ERP status source" status={access.data.erp} />
              </div>
            </section>

            <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4" aria-label="Finance metrics">
              {dashboard.data.metrics.map((metric) => (
                <Card key={metric.metricId}>
                  <CardContent className="p-4">
                    <p className="text-xs text-muted-foreground">{metric.label}</p>
                    <p className="mt-1 text-2xl font-semibold">
                      {metric.unavailable ? "Unavailable" : metric.displayValue}
                    </p>
                    <p className="mt-2 text-xs text-muted-foreground">
                      {metric.temporalMode.toLowerCase()} · {metric.sourceLabel} · definition{" "}
                      {metric.definitionVersion}
                    </p>
                    <StatusBadge className="mt-2" status={metric.freshness} />
                  </CardContent>
                </Card>
              ))}
            </section>

            <DashboardQueues queues={dashboard.data.queues} />

            <Card>
              <CardHeader>
                <CardTitle className="text-base">Authorized engagement months</CardTitle>
              </CardHeader>
              <CardContent>
                {months.data.items.length === 0 ? (
                  <EmptyFinanceState
                    title="No authorized finance months"
                    detail="Server scope returned no engagement month. Changing a URL cannot grant access."
                  />
                ) : (
                  <div className="overflow-x-auto">
                    <table className="w-full min-w-[680px] text-left text-sm">
                      <caption className="sr-only">Authorized finance month readiness</caption>
                      <thead className="border-b text-xs text-muted-foreground">
                        <tr>
                          <th className="p-3">Month</th>
                          <th className="p-3">Engagement</th>
                          <th className="p-3">Vendor</th>
                          <th className="p-3">Readiness</th>
                          <th className="p-3">Freshness</th>
                          <th className="p-3">Action</th>
                        </tr>
                      </thead>
                      <tbody>
                        {months.data.items.map((month) => (
                          <tr key={month.monthId} className="border-b">
                            <td className="p-3 font-medium">{month.monthLabel}</td>
                            <td className="p-3">{month.engagementLabel}</td>
                            <td className="p-3">{month.vendorLabel}</td>
                            <td className="p-3"><StatusBadge status={month.readiness} /></td>
                            <td className="p-3"><StatusBadge status={month.freshness} /></td>
                            <td className="p-3">
                              <Link
                                to="/finance"
                                search={{
                                  monthId: month.monthId,
                                  invoiceId: undefined,
                                  packageId: undefined,
                                }}
                                className="font-medium text-primary hover:underline"
                              >
                                Open workspace
                              </Link>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
                <CursorPagination
                  label="Finance months"
                  hasPrevious={monthPager.hasPrevious}
                  nextCursor={months.data.nextCursor}
                  onPrevious={monthPager.previous}
                  onNext={monthPager.next}
                />
              </CardContent>
            </Card>

            {search.monthId && (
              <MonthWorkspace key={search.monthId} monthId={search.monthId} />
            )}

            <InvoiceQueue
              permissions={access.data.permissions}
              invoices={invoices.data.items}
              selectedInvoiceId={search.invoiceId}
            />
            <CursorPagination
              label="Invoices"
              hasPrevious={invoicePager.hasPrevious}
              nextCursor={invoices.data.nextCursor}
              onPrevious={invoicePager.previous}
              onNext={invoicePager.next}
            />
            {search.invoiceId && (
              <InvoiceWorkspace key={search.invoiceId} invoiceId={search.invoiceId} />
            )}
            {search.packageId && (
              <PackageWorkspace key={search.packageId} packageId={search.packageId} />
            )}
          </div>
        )}
      </FinanceBoundary>
    </div>
  );
}

function MonthWorkspace({ monthId }: { monthId: string }) {
  const query = useFinanceMonth(monthId);
  const createInvoice = useCreateInvoice();
  const generatePackage = useGeneratePackage(monthId);
  const historyPager = useCursorPager();
  const history = usePackageHistory(monthId, historyPager.cursor);
  const [showCreate, setShowCreate] = useState(false);
  const month = query.data;

  return (
    <FinanceBoundary queries={[query, history]}>
      {month && history.data && (
        <section className="space-y-4" aria-labelledby="selected-month-title">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h2 id="selected-month-title" className="text-xl font-semibold">
                {month.month.monthLabel} · {month.month.engagementLabel}
              </h2>
              <p className="text-sm text-muted-foreground">
                Refreshed {formatDateTime(month.month.refreshedAt)}
              </p>
            </div>
            <StatusBadge status={month.month.freshness} />
          </div>

          {month.sourceHandoff ? (
            <Card>
              <CardHeader>
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <CardTitle className="text-base">
                    F04 confirmation/readiness handoff · contract {month.sourceHandoff.contractVersion}
                  </CardTitle>
                  <StatusBadge status={month.sourceHandoff.confirmationDisposition} />
                </div>
              </CardHeader>
              <CardContent>
                <SourceFacts source={month.sourceHandoff.source} />
                {month.sourceHandoff.confirmationDisposition === "EXCEPTION_ACCEPTED" && (
                  <p className="mt-3 text-sm font-medium">
                    Procurement exception accepted. This is not verified confirmation and remains
                    disclosed downstream.
                  </p>
                )}
              </CardContent>
            </Card>
          ) : (
            <EmptyFinanceState
              title="F04 handoff unavailable"
              detail="Package and invoice submission remain blocked; this UI does not fabricate upstream facts."
            />
          )}

          {month.blockers.length > 0 && (
            <div className="rounded-lg border border-destructive/30 bg-destructive/5 p-4" role="alert">
              <p className="font-medium">Current blockers</p>
              <ul className="mt-2 list-disc space-y-1 pl-5 text-sm">
                {month.blockers.map((blocker) => <li key={blocker}>{blocker}</li>)}
              </ul>
            </div>
          )}

          <div className="grid gap-4 xl:grid-cols-2">
            <Card>
              <CardHeader>
                <div className="flex items-center justify-between gap-2">
                  <CardTitle className="text-base">Invoice records</CardTitle>
                  {month.permissions.includes("INVOICE_CREATE") && (
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={monthCommandsDisabled(month.month)}
                      onClick={() => setShowCreate((value) => !value)}
                    >
                      New invoice
                    </Button>
                  )}
                </div>
              </CardHeader>
              <CardContent className="space-y-3">
                {showCreate && (
                  <CreateInvoiceForm
                    monthId={monthId}
                    pending={createInvoice.isPending}
                    disabled={monthCommandsDisabled(month.month)}
                    error={createInvoice.error}
                    onSubmit={(input) => createInvoice.mutate(input, { onSuccess: () => setShowCreate(false) })}
                  />
                )}
                {month.invoices.length === 0 ? (
                  <EmptyFinanceState
                    title="No invoice for this month"
                    detail="An authorized vendor invoice actor can create represented document metadata."
                  />
                ) : (
                  month.invoices.map((invoice) => (
                    <Link
                      key={invoice.invoiceId}
                      to="/finance"
                      search={{ monthId, invoiceId: invoice.invoiceId, packageId: undefined }}
                      className="flex flex-wrap items-center justify-between gap-2 rounded-md border p-3 hover:bg-muted/50"
                    >
                      <span>
                        <span className="font-medium">{invoice.invoiceNumber}</span>
                        <span className="block text-xs text-muted-foreground">
                          server v{invoice.version} · updated {formatDateTime(invoice.updatedAt)}
                        </span>
                      </span>
                      <StatusBadge status={invoice.state} />
                    </Link>
                  ))
                )}
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <div className="flex items-center justify-between gap-2">
                  <CardTitle className="text-base">Evidence package history</CardTitle>
                </div>
              </CardHeader>
              <CardContent className="space-y-3">
                {month.permissions.includes("EVIDENCE_PACKAGE_GENERATE") && (
                  <form
                    className="rounded-md border p-3"
                    onSubmit={(event) => {
                      event.preventDefault();
                      if (
                        monthCommandsDisabled(month.month) ||
                        month.sourceHandoff?.source.freshness === "STALE" ||
                        !month.currentReadinessRunId
                      ) return;
                      const form = new FormData(event.currentTarget);
                      generatePackage.mutate(
                        {
                          expectedMonthVersion: month.month.version,
                          readinessRunId: month.currentReadinessRunId,
                          reason: String(form.get("reason")),
                        },
                        { onSuccess: historyPager.reset },
                      );
                    }}
                  >
                    <fieldset
                      disabled={
                        monthCommandsDisabled(month.month) ||
                        month.sourceHandoff?.source.freshness === "STALE" ||
                        !month.sourceHandoff ||
                        !month.currentReadinessRunId ||
                        generatePackage.isPending
                      }
                    >
                      <Label htmlFor="package-generation-reason">Generation reason</Label>
                      <Input id="package-generation-reason" name="reason" required />
                      <label className="mt-2 flex items-start gap-2 text-xs">
                        <input className="mt-0.5" type="checkbox" required />
                        <span>I reviewed month server v{month.month.version} and readiness run {month.currentReadinessRunId ?? "unavailable"}. Changed inputs create a new immutable version and can supersede downstream readiness.</span>
                      </label>
                      <Button className="mt-3" type="submit" size="sm" variant="outline">
                        <PackagePlus className="mr-1.5 h-4 w-4" aria-hidden="true" />
                        Queue package generation
                      </Button>
                    </fieldset>
                  </form>
                )}
                <FinanceError error={generatePackage.error} compact />
                {history.data.items.length === 0 ? (
                  <EmptyFinanceState
                    title="No generated package"
                    detail="Generation requires an eligible exact F04 handoff and scan-passed invoice evidence."
                  />
                ) : (
                  history.data.items.map((item) => (
                    <Link
                      key={item.packageId}
                      to="/finance"
                      search={{ monthId, invoiceId: undefined, packageId: item.packageId }}
                      className="flex flex-wrap items-center justify-between gap-2 rounded-md border p-3 hover:bg-muted/50"
                    >
                      <span>
                        <span className="font-medium">
                          Package v{item.version} {item.current ? "· current" : "· retained history"}
                        </span>
                        <span className="block font-mono text-xs text-muted-foreground">
                          {shortenHash(item.canonicalInputHash)}
                        </span>
                      </span>
                      <StatusBadge status={item.state} />
                      {["QUEUED", "GENERATING"].includes(item.state) && (
                        <span className="w-full">
                          <Progress value={item.progressPercent} aria-label={`Package version ${item.version} generation progress`} />
                          <span className="mt-1 block text-xs text-muted-foreground">{item.progressPercent}% complete</span>
                        </span>
                      )}
                    </Link>
                  ))
                )}
                <CursorPagination
                  label="Evidence package history"
                  hasPrevious={historyPager.hasPrevious}
                  nextCursor={history.data.nextCursor}
                  onPrevious={historyPager.previous}
                  onNext={historyPager.next}
                />
              </CardContent>
            </Card>
          </div>
        </section>
      )}
    </FinanceBoundary>
  );
}

function InvoiceQueue({
  permissions,
  invoices,
  selectedInvoiceId,
}: {
  permissions: FinancePermission[];
  invoices: Array<{
    invoiceId: string;
    monthId: string;
    invoiceNumber: string;
    engagementLabel: string;
    monthLabel: string;
    state: string;
    version: number;
  }>;
  selectedInvoiceId?: string;
}) {
  return (
    <PermissionNotice permissions={permissions} required="INVOICE_VIEW">
      <Card>
        <CardHeader><CardTitle className="text-base">Scoped invoice queue</CardTitle></CardHeader>
        <CardContent>
          {invoices.length === 0 ? (
            <EmptyFinanceState
              title="No invoices in scope"
              detail="The server returned no invoice records for this authority."
            />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[680px] text-left text-sm">
                <caption className="sr-only">Scoped invoices</caption>
                <thead className="border-b text-xs text-muted-foreground">
                  <tr><th className="p-3">Invoice</th><th className="p-3">Engagement</th><th className="p-3">Month</th><th className="p-3">Version</th><th className="p-3">State</th><th className="p-3">Action</th></tr>
                </thead>
                <tbody>
                  {invoices.map((invoice) => (
                    <tr key={invoice.invoiceId} className={selectedInvoiceId === invoice.invoiceId ? "border-b bg-muted/40" : "border-b"}>
                      <td className="p-3 font-medium">{invoice.invoiceNumber}</td>
                      <td className="p-3">{invoice.engagementLabel}</td>
                      <td className="p-3">{invoice.monthLabel}</td>
                      <td className="p-3">v{invoice.version}</td>
                      <td className="p-3"><StatusBadge status={invoice.state} /></td>
                      <td className="p-3">
                        <Link
                          to="/finance"
                          search={{ monthId: invoice.monthId, invoiceId: invoice.invoiceId, packageId: undefined }}
                          className="font-medium text-primary hover:underline"
                        >
                          Review
                        </Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>
    </PermissionNotice>
  );
}

function InvoiceWorkspace({ invoiceId }: { invoiceId: string }) {
  const query = useFinanceInvoice(invoiceId);
  const invoice = query.data;
  return (
    <FinanceBoundary queries={[query]}>
      {invoice && (
        <section className="space-y-4" aria-labelledby="invoice-detail-title">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h2 id="invoice-detail-title" className="text-xl font-semibold">
                Invoice {invoice.invoiceNumber}
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
          <div className="grid gap-4 xl:grid-cols-3">
            <Card className="xl:col-span-2">
              <CardHeader><CardTitle className="text-base">Represented invoice metadata</CardTitle></CardHeader>
              <CardContent>
                <dl className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                  <Fact label="Invoice date" value={invoice.representedMetadata.invoiceDate} />
                  <Fact label="Billing period" value={`${invoice.representedMetadata.billingPeriodStart} — ${invoice.representedMetadata.billingPeriodEnd}`} />
                  <Fact label="Currency" value={invoice.representedMetadata.currency} />
                  <Fact label="Taxable value (document)" value={invoice.representedMetadata.taxableValue ?? "Not represented"} />
                  <Fact label="Tax (document)" value={invoice.representedMetadata.taxValue ?? "Not represented"} />
                  <Fact label="Total (document)" value={invoice.representedMetadata.totalValue ?? "Not represented"} />
                  <Fact label="Purchase order" value={invoice.representedMetadata.purchaseOrderReference} />
                  <Fact label="Work order" value={invoice.representedMetadata.workOrderReference ?? "Not represented"} />
                </dl>
                <p className="mt-4 text-xs text-muted-foreground">
                  Values are transcribed from the invoice document. No attendance-to-value or person-level commercial calculation is performed.
                </p>
              </CardContent>
            </Card>
            <Card>
              <CardHeader><CardTitle className="text-base">Current document</CardTitle></CardHeader>
              <CardContent>
                {invoice.currentDocument ? (
                  <dl className="space-y-3 text-sm">
                    <Fact label="Safe filename" value={invoice.currentDocument.fileName} />
                    <Fact label="Scan" value={invoice.currentDocument.scanStatus} badge />
                    <Fact label="Object version" value={invoice.currentDocument.objectVersion} mono />
                    <Fact label="SHA-256" value={shortenHash(invoice.currentDocument.sha256)} mono />
                  </dl>
                ) : (
                  <p className="text-sm text-muted-foreground">No document uploaded.</p>
                )}
              </CardContent>
            </Card>
          </div>

          <DocumentUpload invoice={invoice} />

          <Card>
            <CardHeader>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <CardTitle className="text-base">Rule-level readiness</CardTitle>
                <ReadinessAction invoice={invoice} />
              </div>
            </CardHeader>
            <CardContent className="space-y-4">
              {invoice.readiness && (
                <div className="rounded-md bg-muted/40 p-3 text-xs">
                  Run {invoice.readiness.runId} · policy {invoice.readiness.policyVersion} · input{" "}
                  <span className="font-mono">{shortenHash(invoice.readiness.inputHash)}</span> ·{" "}
                  {invoice.readiness.eligibleForSubmission ? "eligible" : "blocked"}
                </div>
              )}
              <ReadinessChecklist rules={invoice.readiness?.rules ?? []} />
              <SubmitAction invoice={invoice} />
            </CardContent>
          </Card>

          <div className="grid gap-4 xl:grid-cols-2">
            <Card>
              <CardHeader><CardTitle className="text-base">Version lineage</CardTitle></CardHeader>
              <CardContent>
                <ol className="space-y-3">
                  {invoice.versions.map((version) => (
                    <li key={version.versionId} className="rounded-md border p-3 text-sm">
                      <div className="flex items-center justify-between gap-2">
                        <span className="font-medium">v{version.version} · {version.kind}</span>
                        <StatusBadge status={version.state} />
                      </div>
                      <p className="mt-1 text-xs text-muted-foreground">
                        {version.createdByDisplay} · {formatDateTime(version.createdAt)}
                        {version.reason ? ` · ${version.reason}` : ""}
                      </p>
                    </li>
                  ))}
                </ol>
              </CardContent>
            </Card>
            <PaymentTimeline
              permissions={invoice.permissions}
              events={invoice.paymentTimeline}
            />
          </div>
        </section>
      )}
    </FinanceBoundary>
  );
}

function DocumentUpload({ invoice }: { invoice: InvoiceView }) {
  const replace = Boolean(invoice.currentDocument);
  const upload = useUploadInvoiceDocument(invoice.invoiceId, replace);
  const inputRef = useRef<HTMLInputElement>(null);
  if (!invoice.permissions.includes(replace ? "INVOICE_REPLACE" : "INVOICE_UPLOAD")) return null;
  const blocked = invoiceCommandsDisabled(invoice);
  const scanStatus = invoice.currentDocument?.scanStatus;

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (blocked) return;
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const file = inputRef.current?.files?.[0];
    const reason = String(form.get("reason") ?? "").trim();
    const confirmed = form.get("confirmed") === "on";
    if (!file || !reason || !confirmed) return;
    const input: UploadInvoiceDocumentInput = {
      expectedVersion: invoice.version,
      file,
      classification: String(form.get("classification")),
      retentionPolicy: String(form.get("retentionPolicy")),
      source: "VENDOR_UPLOAD",
      reason,
    };
    upload.mutate(input, { onSuccess: () => formElement.reset() });
  }

  return (
    <Card>
      <CardHeader><CardTitle className="text-base">{replace ? "Replace invoice document" : "Upload invoice document"}</CardTitle></CardHeader>
      <CardContent>
        {scanStatus && scanStatus !== "PASSED" && (
          <p className="mb-3 rounded-md border border-warning/40 bg-warning/5 p-3 text-sm" role="status">
            The current document is {scanStatus.toLowerCase()}. It remains unavailable for
            preview, package and download. An authorized replacement creates a separate immutable
            version and is still permitted while this invoice is current.
          </p>
        )}
        <form className="grid gap-4 md:grid-cols-2" onSubmit={submit}>
          <div className="md:col-span-2">
            <Label htmlFor="invoice-file">Invoice file</Label>
            <Input ref={inputRef} id="invoice-file" name="file" type="file" accept=".pdf,.png,.jpg,.jpeg" required disabled={blocked || upload.isPending} />
            <p className="mt-1 text-xs text-muted-foreground">The browser sends this directly to the authenticated API. Raw evidence is not retained in application state.</p>
          </div>
          <div><Label htmlFor="classification">Classification</Label><Input id="classification" name="classification" defaultValue="CONFIDENTIAL" required disabled={blocked} /></div>
          <div><Label htmlFor="retention-policy">Retention policy</Label><Input id="retention-policy" name="retentionPolicy" defaultValue="INVOICE_STANDARD" required disabled={blocked} /></div>
          <div className="md:col-span-2"><Label htmlFor="upload-reason">Reason</Label><Textarea id="upload-reason" name="reason" required disabled={blocked} /></div>
          <label className="flex items-start gap-2 text-sm md:col-span-2"><input name="confirmed" type="checkbox" required disabled={blocked} className="mt-1" /><span>I confirm this action creates a new immutable document version against server invoice v{invoice.version}; prior evidence is retained.</span></label>
          <div className="md:col-span-2"><FinanceError error={upload.error} compact /><Button type="submit" disabled={blocked || upload.isPending}><Upload className="mr-1.5 h-4 w-4" aria-hidden="true" />{replace ? "Create replacement version" : "Upload for scan"}</Button></div>
        </form>
      </CardContent>
    </Card>
  );
}

function ReadinessAction({ invoice }: { invoice: InvoiceView }) {
  const mutation = useEvaluateReadiness(invoice.invoiceId);
  if (!invoice.permissions.includes("INVOICE_VIEW")) return null;
  const blocked = invoiceCommandsDisabled(invoice);
  return (
    <div>
      <Button size="sm" variant="outline" disabled={blocked || mutation.isPending} onClick={() => {
        if (!blocked) mutation.mutate({ expectedVersion: invoice.version });
      }}>
        <RefreshCcw className="mr-1.5 h-4 w-4" aria-hidden="true" />Evaluate exact v{invoice.version}
      </Button>
      <FinanceError error={mutation.error} compact />
    </div>
  );
}

function SubmitAction({ invoice }: { invoice: InvoiceView }) {
  const mutation = useSubmitInvoice(invoice.invoiceId);
  const run = invoice.readiness;
  const packageView = invoice.linkedPackage;
  if (!invoice.permissions.includes("INVOICE_SUBMIT")) return null;
  const enabled = Boolean(
    run?.eligibleForSubmission &&
    !run.stale &&
    packageView?.state === "AVAILABLE" &&
    packageView.current &&
    !invoiceCommandsDisabled(invoice)
  );
  return (
    <form
      className="rounded-lg border p-4"
      onSubmit={(event) => {
        event.preventDefault();
        if (!enabled || !run || !packageView) return;
        const form = new FormData(event.currentTarget);
        mutation.mutate({
          expectedVersion: invoice.version,
          packageId: packageView.packageId,
          packageVersion: packageView.version,
          readinessRunId: run.runId,
          acknowledgment: form.get("acknowledgment") === "on",
          reason: String(form.get("reason")),
        });
      }}
    >
      <p className="font-medium">Submit exact version to Procurement</p>
      <p className="mt-1 text-xs text-muted-foreground">
        Invoice v{invoice.version} · package {packageView ? `v${packageView.version}` : "unavailable"} · readiness {run?.runId ?? "unavailable"}. Submission locks this path; corrections create retained lineage.
      </p>
      <Label className="mt-3 block" htmlFor="submit-reason">Submission reason</Label>
      <Input id="submit-reason" name="reason" required disabled={!enabled} />
      <label className="mt-3 flex items-start gap-2 text-sm"><input name="acknowledgment" type="checkbox" required disabled={!enabled} className="mt-1" /><span>I reviewed the exact source, invoice, package and readiness versions and understand the downstream Procurement consequence.</span></label>
      <FinanceError error={mutation.error} compact />
      <Button className="mt-3" type="submit" disabled={!enabled || mutation.isPending}><Send className="mr-1.5 h-4 w-4" aria-hidden="true" />Submit to Procurement</Button>
    </form>
  );
}

function PackageWorkspace({ packageId }: { packageId: string }) {
  const query = usePackage(packageId);
  const packageView = query.data;
  const [againstId, setAgainstId] = useState("");
  const historyPager = useCursorPager();
  const accessPager = useCursorPager();
  const sharePager = useCursorPager();
  const history = usePackageHistory(packageView?.monthId ?? "", historyPager.cursor);
  const diff = usePackageDiff(packageId, againstId);
  const canAudit =
    packageView?.permissions.includes("EVIDENCE_PACKAGE_ACCESS_AUDIT") ?? false;
  const access = usePackageAccess(packageId, canAudit, accessPager.cursor);
  const shares = usePackageShares(packageId, canAudit, sharePager.cursor);
  const createShare = useCreatePackageShare(packageId);
  const revokeShare = useRevokePackageShare(packageId);
  const download = usePackageDownload();
  return (
    <FinanceBoundary
      queries={[
        query,
        ...(packageView ? [history] : []),
        ...(canAudit ? [access, shares] : []),
      ]}
    >
      {packageView && (
        <section className="space-y-4" aria-labelledby="package-detail-title">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div><h2 id="package-detail-title" className="text-xl font-semibold">Evidence package v{packageView.version}</h2><p className="text-sm text-muted-foreground">{packageView.engagementLabel} · {packageView.monthLabel}</p></div>
            <StatusBadge status={packageView.state} />
          </div>
          {!packageView.current && <div className="rounded-lg border border-warning/40 bg-warning/5 p-3 text-sm" role="status">Superseded, immutable historical version. Compare it with the current package before relying on it.</div>}
          {!packageView.integrityVerified && <div className="rounded-lg border border-destructive/30 bg-destructive/5 p-3 text-sm" role="alert">Integrity verification failed. Preview and download remain blocked.</div>}
          <Card><CardHeader><CardTitle className="text-base">Canonical package identity</CardTitle></CardHeader><CardContent><dl className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4"><Fact label="Canonical input hash" value={shortenHash(packageView.canonicalInputHash)} mono /><Fact label="Policy" value={packageView.policyVersion} /><Fact label="Template" value={packageView.templateVersion} /><Fact label="Generated" value={formatDateTime(packageView.generatedAt)} /></dl>{packageView.provenanceDisclosure && <p className="mt-3 text-sm">{packageView.provenanceDisclosure}</p>}</CardContent></Card>
          <Card><CardHeader><CardTitle className="text-base">Immutable manifest sources</CardTitle></CardHeader><CardContent className="space-y-3">{packageView.sources.map((source) => <div key={`${source.sourceType}-${source.sourceId}-${source.version}`} className="rounded-md border p-3"><SourceFacts source={source} /></div>)}</CardContent></Card>
          <Card>
            <CardHeader><CardTitle className="text-base">Immutable manifest items</CardTitle></CardHeader>
            <CardContent className="p-0">
              {packageView.manifestItems.length === 0 ? (
                <div className="p-4">
                  <EmptyFinanceState
                    title="No manifest items returned"
                    detail="Artifact-level lineage is unavailable; do not rely on rendered outputs without it."
                  />
                </div>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full min-w-[980px] text-left text-xs">
                    <caption className="sr-only">
                      Immutable package manifest items and retained object lineage
                    </caption>
                    <thead className="border-b bg-muted/40 text-muted-foreground">
                      <tr>
                        <th className="p-3">Item</th>
                        <th className="p-3">Source version</th>
                        <th className="p-3">Object version</th>
                        <th className="p-3">Media / size</th>
                        <th className="p-3">SHA-256</th>
                        <th className="p-3">Classification / retention</th>
                      </tr>
                    </thead>
                    <tbody>
                      {packageView.manifestItems.map((item) => (
                        <tr key={item.itemId} className="border-b align-top">
                          <td className="p-3">
                            <span className="font-medium">{item.logicalType}</span>
                            <span className="block">{item.safeName}</span>
                          </td>
                          <td className="p-3">
                            {item.source.sourceType} ·{" "}
                            <span className="font-mono">{item.source.version}</span>
                            <span className="block text-muted-foreground">
                              {item.source.provenance} · {item.source.temporalMode.toLowerCase()}
                            </span>
                          </td>
                          <td className="p-3 font-mono">{item.objectVersion}</td>
                          <td className="p-3">
                            {item.mimeType}
                            <span className="block text-muted-foreground">
                              {item.sizeBytes.toLocaleString()} bytes
                            </span>
                          </td>
                          <td className="p-3 font-mono">{shortenHash(item.sha256)}</td>
                          <td className="p-3">
                            {item.classification}
                            <span className="block text-muted-foreground">
                              {item.retentionPolicy}
                            </span>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </CardContent>
          </Card>
          <Card>
            <CardHeader><CardTitle className="text-base">Version diff</CardTitle></CardHeader>
            <CardContent className="space-y-3">
              <div>
                <Label htmlFor="package-diff-version">Compare with retained package</Label>
                <select id="package-diff-version" className="h-9 w-full rounded-md border bg-background px-3 text-sm sm:max-w-sm" value={againstId} onChange={(event) => setAgainstId(event.target.value)}>
                  <option value="">Select version</option>
                  {history.data?.items.filter((item) => item.packageId !== packageId).map((item) => <option key={item.packageId} value={item.packageId}>Package v{item.version} · {item.state.toLowerCase()}</option>)}
                </select>
              </div>
              {history.data && (
                <CursorPagination
                  label="Package versions"
                  hasPrevious={historyPager.hasPrevious}
                  nextCursor={history.data.nextCursor}
                  onPrevious={historyPager.previous}
                  onNext={historyPager.next}
                />
              )}
              {againstId && diff.isPending && <p role="status" className="text-sm text-muted-foreground">Loading immutable version diff…</p>}
              <FinanceError error={diff.error} compact />
              {diff.data && (
                <div className="grid gap-3 md:grid-cols-3">
                  <DiffList title="Added" items={diff.data.added.map((item) => `${item.logicalType} · ${item.sourceId} v${item.version}`)} />
                  <DiffList title="Changed" items={diff.data.changed.map((item) => `${item.logicalType} · ${item.sourceId} v${item.fromVersion} → v${item.toVersion}`)} />
                  <DiffList title="Removed" items={diff.data.removed.map((item) => `${item.logicalType} · ${item.sourceId} v${item.version}`)} />
                </div>
              )}
            </CardContent>
          </Card>
          <Card><CardHeader><CardTitle className="text-base">Rendered artifacts</CardTitle></CardHeader><CardContent className="space-y-3">
            {packageView.artifacts.map((artifact) => (
              <div key={artifact.artifactId} className="flex flex-wrap items-center justify-between gap-3 rounded-md border p-3 text-sm">
                <div><p className="font-medium">{artifact.label} · {artifact.format}</p><p className="font-mono text-xs text-muted-foreground">{shortenHash(artifact.sha256)} · {artifact.sizeBytes.toLocaleString()} bytes</p>{artifact.scanStatus !== "PASSED" && <p className="mt-1 text-xs text-warning">Unavailable until the scan passes. Refresh for scanner status or contact the evidence owner if it fails or is quarantined.</p>}</div>
                <div className="flex items-center gap-2"><StatusBadge status={artifact.scanStatus} />
                  {packageView.permissions.includes("EVIDENCE_PACKAGE_DOWNLOAD") && (
                    <Button size="sm" variant="outline" disabled={!artifact.downloadAllowed || artifact.scanStatus !== "PASSED" || !packageView.integrityVerified || download.isPending} onClick={() => download.mutate({ packageId, artifactId: artifact.artifactId })}><Download className="mr-1.5 h-4 w-4" aria-hidden="true" />Download</Button>
                  )}
                </div>
              </div>
            ))}
            <FinanceError error={download.error} compact />
          </CardContent></Card>
          {shares.data && (
            <Card>
              <CardHeader>
                <CardTitle className="text-base">Controlled package sharing</CardTitle>
              </CardHeader>
              <CardContent className="space-y-4">
                {packageView.permissions.includes("EVIDENCE_PACKAGE_DOWNLOAD") && (
                  <form
                    className="grid gap-3 rounded-md border p-3 md:grid-cols-2"
                    onSubmit={(event) => {
                      event.preventDefault();
                      const formElement = event.currentTarget;
                      if (
                        packageView.state !== "AVAILABLE" ||
                        !packageView.integrityVerified ||
                        !packageView.current
                      ) {
                        return;
                      }
                      const form = new FormData(formElement);
                      const expiresAt = localDateTimeToIso(String(form.get("expiresAt")));
                      if (!expiresAt || form.get("confirmed") !== "on") return;
                      createShare.mutate(
                        {
                          recipientSubject: String(form.get("recipientSubject")).trim(),
                          accessScope: String(form.get("accessScope")) as "VIEW" | "DOWNLOAD",
                          expiresAt,
                          reason: String(form.get("reason")).trim(),
                        },
                        {
                          onSuccess: () => {
                            formElement.reset();
                            sharePager.reset();
                          },
                        },
                      );
                    }}
                  >
                    <fieldset
                      className="contents"
                      disabled={
                        packageView.state !== "AVAILABLE" ||
                        !packageView.integrityVerified ||
                        !packageView.current ||
                        createShare.isPending
                      }
                    >
                      <div>
                        <Label htmlFor="package-share-recipient">Authenticated recipient subject</Label>
                        <Input id="package-share-recipient" name="recipientSubject" required />
                      </div>
                      <div>
                        <Label htmlFor="package-share-scope">Access scope</Label>
                        <select
                          id="package-share-scope"
                          name="accessScope"
                          className="h-9 w-full rounded-md border bg-background px-3 text-sm"
                          required
                        >
                          <option value="VIEW">View</option>
                          <option value="DOWNLOAD">Download</option>
                        </select>
                      </div>
                      <div>
                        <Label htmlFor="package-share-expiry">Expires at</Label>
                        <Input
                          id="package-share-expiry"
                          name="expiresAt"
                          type="datetime-local"
                          required
                        />
                      </div>
                      <div>
                        <Label htmlFor="package-share-reason">Business reason</Label>
                        <Input id="package-share-reason" name="reason" required />
                      </div>
                      <label className="flex items-start gap-2 text-sm md:col-span-2">
                        <input name="confirmed" type="checkbox" required className="mt-1" />
                        <span>
                          I confirm the recipient identity, least-privilege scope and expiry for
                          current immutable package v{packageView.version}. Revocation is audited.
                        </span>
                      </label>
                      <div className="md:col-span-2">
                        <FinanceError error={createShare.error} compact />
                        <Button type="submit" size="sm">
                          Create controlled share
                        </Button>
                      </div>
                    </fieldset>
                  </form>
                )}
                {shares.data.items.length === 0 ? (
                  <EmptyFinanceState
                    title="No package shares"
                    detail="Only explicit, expiring grants to active authenticated identities appear here."
                  />
                ) : (
                  <ol className="space-y-3">
                    {shares.data.items.map((share) => (
                      <li key={share.shareId} className="rounded-md border p-3 text-sm">
                        <div className="flex flex-wrap items-start justify-between gap-2">
                          <div>
                            <p className="font-medium">
                              {share.recipientSubject} · {share.accessScope.toLowerCase()}
                            </p>
                            <p className="mt-1 text-xs text-muted-foreground">
                              Created by {share.createdByDisplay} on{" "}
                              {formatDateTime(share.createdAt)} · expires{" "}
                              {formatDateTime(share.expiresAt)}
                            </p>
                          </div>
                          <StatusBadge status={share.revoked ? "REVOKED" : "ACTIVE"} />
                        </div>
                        {share.revoked ? (
                          <p className="mt-2 text-xs">
                            Revoked {formatDateTime(share.revokedAt)}
                            {share.correlationId ? ` · reference ${share.correlationId}` : ""}
                          </p>
                        ) : packageView.permissions.includes("EVIDENCE_PACKAGE_DOWNLOAD") ? (
                          <form
                            className="mt-3 flex flex-col gap-2 sm:flex-row"
                            onSubmit={(event) => {
                              event.preventDefault();
                              const form = new FormData(event.currentTarget);
                              if (form.get("confirmed") !== "on") return;
                              revokeShare.mutate({
                                shareId: share.shareId,
                                reason: String(form.get("reason")).trim(),
                              });
                            }}
                          >
                            <Input
                              name="reason"
                              aria-label={`Revocation reason for ${share.recipientSubject}`}
                              placeholder="Revocation reason"
                              required
                              disabled={revokeShare.isPending}
                            />
                            <label className="flex items-center gap-2 whitespace-nowrap text-xs">
                              <input
                                name="confirmed"
                                type="checkbox"
                                required
                                disabled={revokeShare.isPending}
                              />
                              Confirm revoke
                            </label>
                            <Button
                              type="submit"
                              size="sm"
                              variant="outline"
                              disabled={revokeShare.isPending}
                            >
                              Revoke share
                            </Button>
                          </form>
                        ) : null}
                      </li>
                    ))}
                  </ol>
                )}
                <FinanceError error={revokeShare.error} compact />
                <CursorPagination
                  label="Package shares"
                  hasPrevious={sharePager.hasPrevious}
                  nextCursor={shares.data.nextCursor}
                  onPrevious={sharePager.previous}
                  onNext={sharePager.next}
                />
              </CardContent>
            </Card>
          )}
          {access.data && (
            <Card>
              <CardHeader><CardTitle className="text-base">Access history</CardTitle></CardHeader>
              <CardContent>
                {access.data.items.length === 0 ? (
                  <EmptyFinanceState title="No access events" detail="Authorized views, downloads, shares, revocations and denials appear here." />
                ) : (
                  <ol className="space-y-2">{access.data.items.map((event) => <li key={event.accessId} className="rounded-md border p-3 text-sm"><div className="flex items-center justify-between gap-2"><span>{event.actorDisplay} · {event.action}</span><span className="text-xs text-muted-foreground">{formatDateTime(event.recordedAt)}</span></div><p className="text-xs text-muted-foreground">{event.authorityDisplay}{event.expiresAt ? ` · expires ${formatDateTime(event.expiresAt)}` : ""}</p></li>)}</ol>
                )}
                <CursorPagination
                  label="Package access history"
                  hasPrevious={accessPager.hasPrevious}
                  nextCursor={access.data.nextCursor}
                  onPrevious={accessPager.previous}
                  onNext={accessPager.next}
                />
              </CardContent>
            </Card>
          )}
        </section>
      )}
    </FinanceBoundary>
  );
}

function CreateInvoiceForm({
  monthId,
  pending,
  disabled,
  error,
  onSubmit,
}: {
  monthId: string;
  pending: boolean;
  disabled: boolean;
  error: Error | null;
  onSubmit: (input: CreateInvoiceInput) => void;
}) {
  return (
    <form className="space-y-3 rounded-md border p-3" onSubmit={(event) => {
      event.preventDefault();
      if (disabled) return;
      const form = new FormData(event.currentTarget);
      const metadata: InvoiceRepresentedMetadata = {
        invoiceNumber: String(form.get("invoiceNumber")),
        invoiceDate: String(form.get("invoiceDate")),
        billingPeriodStart: String(form.get("billingPeriodStart")),
        billingPeriodEnd: String(form.get("billingPeriodEnd")),
        currency: String(form.get("currency")),
        taxableValue: String(form.get("taxableValue") || "") || null,
        taxValue: String(form.get("taxValue") || "") || null,
        totalValue: String(form.get("totalValue") || "") || null,
        purchaseOrderReference: String(form.get("purchaseOrderReference")),
        workOrderReference: String(form.get("workOrderReference") || "") || null,
      };
      onSubmit({
        monthId,
        documentKind: String(form.get("documentKind")) as CreateInvoiceInput["documentKind"],
        relatedInvoiceId: String(form.get("relatedInvoiceId") || "") || null,
        representedMetadata: metadata,
      });
    }}>
      <fieldset disabled={disabled || pending} className="space-y-3">
        <p className="text-sm font-medium">Create represented invoice metadata</p>
        <div><Label htmlFor="new-kind">Document lineage</Label><select id="new-kind" name="documentKind" required className="h-9 w-full rounded-md border bg-background px-3 text-sm"><option value="PRIMARY">Primary invoice</option><option value="CORRECTION">Correction / replacement invoice</option><option value="CREDIT_NOTE">Credit note</option><option value="DEBIT_NOTE">Debit note</option></select><p className="mt-1 text-xs text-muted-foreground">Corrections and notes require server-validated original lineage; prior versions are never overwritten.</p></div>
        <div><Label htmlFor="new-related-invoice">Related original invoice ID (correction/note)</Label><Input id="new-related-invoice" name="relatedInvoiceId" /></div>
        <div className="grid gap-3 sm:grid-cols-2">
          <div><Label htmlFor="new-number">Invoice number</Label><Input id="new-number" name="invoiceNumber" required /></div>
          <div><Label htmlFor="new-date">Invoice date</Label><Input id="new-date" name="invoiceDate" type="date" required /></div>
          <div><Label htmlFor="new-start">Billing period start</Label><Input id="new-start" name="billingPeriodStart" type="date" required /></div>
          <div><Label htmlFor="new-end">Billing period end</Label><Input id="new-end" name="billingPeriodEnd" type="date" required /></div>
          <div><Label htmlFor="new-currency">Currency</Label><Input id="new-currency" name="currency" defaultValue="INR" maxLength={3} required /></div>
          <div><Label htmlFor="new-po">Purchase order</Label><Input id="new-po" name="purchaseOrderReference" required /></div>
          <div><Label htmlFor="new-taxable">Taxable value (document)</Label><Input id="new-taxable" name="taxableValue" inputMode="decimal" /></div>
          <div><Label htmlFor="new-tax">Tax (document)</Label><Input id="new-tax" name="taxValue" inputMode="decimal" /></div>
          <div><Label htmlFor="new-total">Total (document)</Label><Input id="new-total" name="totalValue" inputMode="decimal" /></div>
          <div><Label htmlFor="new-wo">Work order</Label><Input id="new-wo" name="workOrderReference" /></div>
        </div>
        <p className="text-xs text-muted-foreground">These optional values are copied from the invoice document and are never derived from people, attendance or delivery evidence.</p>
        <FinanceError error={error} compact />
        <Button type="submit" size="sm"><FileCheck2 className="mr-1.5 h-4 w-4" aria-hidden="true" />Create draft</Button>
      </fieldset>
    </form>
  );
}

function DiffList({ title, items }: { title: string; items: string[] }) {
  return (
    <div className="rounded-md border p-3 text-sm">
      <p className="font-medium">{title} ({items.length})</p>
      {items.length === 0 ? <p className="mt-1 text-xs text-muted-foreground">None</p> : <ul className="mt-2 list-disc space-y-1 pl-5 text-xs">{items.map((item) => <li key={item}>{item}</li>)}</ul>}
    </div>
  );
}
