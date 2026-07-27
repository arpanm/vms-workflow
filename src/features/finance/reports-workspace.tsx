import { Download, FileBarChart, LoaderCircle } from "lucide-react";
import { useState } from "react";

import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Progress } from "@/components/ui/progress";
import { Textarea } from "@/components/ui/textarea";
import {
  CursorPagination,
  DashboardQueues,
  EmptyFinanceState,
  FinanceBoundary,
  FinanceError,
  FinanceNav,
} from "@/features/finance/components";
import type { CreateExportInput } from "@/features/finance/contracts";
import {
  useCreateExport,
  useExportDownload,
  useFinanceDashboard,
  useReports,
} from "@/features/finance/hooks";
import { useCursorPager } from "@/features/finance/pagination";
import { formatDateTime, shortenHash } from "@/features/finance/presentation";

export function FinanceReportsWorkspace() {
  const dashboard = useFinanceDashboard();
  const reportsPager = useCursorPager();
  const reports = useReports(reportsPager.cursor);
  const createExport = useCreateExport();
  const download = useExportDownload();
  const [selectedReport, setSelectedReport] = useState("");

  return (
    <div>
      <PageHeader
        title="Finance dashboards, reports and exports"
        description="Permission-scoped metrics with explicit definition, source freshness, live/snapshot mode and auditable asynchronous export."
      >
        <FinanceNav />
      </PageHeader>
      <FinanceBoundary queries={[dashboard, reports]}>
        {dashboard.data && reports.data && (
          <div className="space-y-6 p-4 sm:p-6">
            <div
              className={`rounded-lg border p-3 text-sm ${
                dashboard.data.freshness === "STALE"
                  ? "border-warning/40 bg-warning/5"
                  : "bg-muted/30"
              }`}
              role={dashboard.data.freshness === "STALE" ? "alert" : "status"}
            >
              {dashboard.data.personaLabel} dashboard · refreshed{" "}
              {formatDateTime(dashboard.data.refreshedAt)} ·{" "}
              {dashboard.data.freshness.toLowerCase()}. Zero is displayed as zero; unavailable data
              is labelled unavailable.
            </div>

            <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4" aria-label="Scoped metrics">
              {dashboard.data.metrics.map((metric) => (
                <Card key={metric.metricId}>
                  <CardContent className="p-4">
                    <p className="text-xs text-muted-foreground">{metric.label}</p>
                    <p className="mt-1 text-2xl font-semibold">
                      {metric.unavailable ? "Unavailable" : metric.displayValue}
                    </p>
                    <p className="mt-2 text-xs">
                      {metric.temporalMode.toLowerCase()} · {metric.sourceLabel}
                    </p>
                    <p className="mt-1 text-xs text-muted-foreground">
                      definition {metric.definitionVersion} · policy {metric.policyVersion}
                    </p>
                    <StatusBadge className="mt-2" status={metric.freshness} />
                  </CardContent>
                </Card>
              ))}
            </section>

            <DashboardQueues queues={dashboard.data.queues} />

            <div className="grid gap-4 xl:grid-cols-[1fr_1.5fr]">
              <Card>
                <CardHeader><CardTitle className="text-base">Request export</CardTitle></CardHeader>
                <CardContent>
                  {reports.data.permissions.includes("REPORT_EXPORT") ? (
                    <form
                      className="space-y-4"
                      onSubmit={(event) => {
                        event.preventDefault();
                        if (dashboard.data.freshness === "STALE") return;
                        const form = new FormData(event.currentTarget);
                        const definition = reports.data.definitions.find(
                          (item) => item.reportId === String(form.get("reportId")),
                        );
                        if (!definition) return;
                        const filters: CreateExportInput["filters"] = {};
                        const month = String(form.get("month") || "").trim();
                        const engagementId = String(form.get("engagementId") || "").trim();
                        if (month) filters.month = month;
                        if (engagementId) filters.engagementId = engagementId;
                        const input: CreateExportInput = {
                          reportId: definition.reportId,
                          reportVersion: definition.version,
                          format: String(form.get("format")) as CreateExportInput["format"],
                          temporalMode: String(form.get("temporalMode")) as CreateExportInput["temporalMode"],
                          filters,
                          reason: String(form.get("reason")),
                        };
                        createExport.mutate(input);
                      }}
                    >
                      <fieldset
                        disabled={dashboard.data.freshness === "STALE" || createExport.isPending}
                        className="space-y-4"
                      >
                      <div>
                        <Label htmlFor="report-id">Report and version</Label>
                        <select
                          id="report-id"
                          name="reportId"
                          required
                          value={selectedReport}
                          onChange={(event) => setSelectedReport(event.target.value)}
                          className="h-9 w-full rounded-md border bg-background px-3 text-sm"
                        >
                          <option value="">Select report</option>
                          {reports.data.definitions.map((definition) => (
                            <option key={definition.reportId} value={definition.reportId}>
                              {definition.name} · v{definition.version}
                            </option>
                          ))}
                        </select>
                      </div>
                      <div className="grid gap-3 sm:grid-cols-2">
                        <div>
                          <Label htmlFor="report-format">Format</Label>
                          <select id="report-format" name="format" required className="h-9 w-full rounded-md border bg-background px-3 text-sm">
                            {(reports.data.definitions.find((item) => item.reportId === selectedReport)?.availableFormats ?? ["CSV"]).map((format) => <option key={format} value={format}>{format}</option>)}
                          </select>
                        </div>
                        <div>
                          <Label htmlFor="temporal-mode">Data mode</Label>
                          <select id="temporal-mode" name="temporalMode" required className="h-9 w-full rounded-md border bg-background px-3 text-sm">
                            <option value="CURRENT">Current / live</option>
                            <option value="SNAPSHOT">Historical snapshot</option>
                          </select>
                        </div>
                      </div>
                      <div><Label htmlFor="report-month">Month filter</Label><Input id="report-month" name="month" type="month" /></div>
                      <div><Label htmlFor="report-engagement">Engagement ID filter</Label><Input id="report-engagement" name="engagementId" /></div>
                      <div><Label htmlFor="export-reason">Export reason</Label><Textarea id="export-reason" name="reason" required /></div>
                      <label className="flex items-start gap-2 text-sm"><input type="checkbox" required className="mt-1" /><span>I confirm the exact filters, temporal mode and report version. Server export masking remains authoritative and matches screen scope.</span></label>
                      <FinanceError error={createExport.error} compact />
                      <Button type="submit" disabled={!selectedReport}>
                        <FileBarChart className="mr-1.5 h-4 w-4" aria-hidden="true" />
                        Queue private export
                      </Button>
                      </fieldset>
                    </form>
                  ) : (
                    <p className="text-sm text-muted-foreground">
                      The server did not grant report export permission for this authority.
                    </p>
                  )}
                </CardContent>
              </Card>

              <Card>
                <CardHeader><CardTitle className="text-base">Asynchronous export queue</CardTitle></CardHeader>
                <CardContent className="space-y-3">
                  {reports.data.exports.items.length === 0 ? (
                    <EmptyFinanceState
                      title="No export jobs"
                      detail="Queued, running, retry, dead-letter, ready and expired jobs appear here."
                    />
                  ) : (
                    reports.data.exports.items.map((job) => (
                      <article key={job.exportId} className="rounded-md border p-4">
                        <div className="flex flex-wrap items-start justify-between gap-2">
                          <div>
                            <h3 className="font-medium">{job.reportName} · {job.format}</h3>
                            <p className="text-xs text-muted-foreground">
                              report v{job.reportVersion} · {job.temporalMode.toLowerCase()} · {job.filterSummary}
                            </p>
                          </div>
                          <StatusBadge status={job.status} />
                        </div>
                        {["QUEUED", "RUNNING", "RETRY_SCHEDULED"].includes(job.status) && (
                          <div className="mt-3">
                            <Progress value={job.progressPercent} aria-label={`${job.reportName} export progress`} />
                            <p className="mt-1 text-xs text-muted-foreground">
                              {job.progressPercent}% complete
                              {job.status === "RETRY_SCHEDULED" ? " · retry scheduled" : ""}
                            </p>
                          </div>
                        )}
                        <dl className="mt-3 grid gap-2 text-xs sm:grid-cols-2">
                          <div><dt className="text-muted-foreground">Generated</dt><dd>{formatDateTime(job.generatedAt)}</dd></div>
                          <div><dt className="text-muted-foreground">Expires</dt><dd>{formatDateTime(job.expiresAt)}</dd></div>
                          <div><dt className="text-muted-foreground">Rows</dt><dd>{job.rowCount ?? "Unavailable"}</dd></div>
                          <div><dt className="text-muted-foreground">SHA-256</dt><dd className="font-mono">{job.sha256 ? shortenHash(job.sha256) : "Unavailable"}</dd></div>
                        </dl>
                        {job.status === "DEAD_LETTER" && (
                          <p className="mt-2 text-sm text-destructive" role="alert">
                            Export reached dead letter. Contact an authorized operator
                            {job.correlationId ? ` with reference ${job.correlationId}` : ""}.
                          </p>
                        )}
                        {job.status === "EXPIRED" && (
                          <p className="mt-2 text-sm" role="status">
                            Download expired. Create a new scoped export; no URL is retained.
                          </p>
                        )}
                        {job.downloadAllowed && job.status === "READY" && (
                          <Button
                            className="mt-3"
                            size="sm"
                            variant="outline"
                            disabled={download.isPending}
                            onClick={() => download.mutate(job.exportId)}
                          >
                            {download.isPending ? <LoaderCircle className="mr-1.5 h-4 w-4 animate-spin" aria-hidden="true" /> : <Download className="mr-1.5 h-4 w-4" aria-hidden="true" />}
                            Authenticated download
                          </Button>
                        )}
                      </article>
                    ))
                  )}
                  <FinanceError error={download.error} compact />
                  <CursorPagination
                    label="Export jobs"
                    hasPrevious={reportsPager.hasPrevious}
                    nextCursor={reports.data.exports.nextCursor}
                    onPrevious={reportsPager.previous}
                    onNext={reportsPager.next}
                  />
                </CardContent>
              </Card>
            </div>

            <Card>
              <CardHeader><CardTitle className="text-base">Report catalog</CardTitle></CardHeader>
              <CardContent className="grid gap-3 md:grid-cols-2">
                {reports.data.definitions.map((definition) => (
                  <article key={definition.reportId} className="rounded-md border p-3 text-sm">
                    <h3 className="font-medium">{definition.name} · v{definition.version}</h3>
                    <p className="mt-1 text-muted-foreground">{definition.description}</p>
                    <p className="mt-2 text-xs">
                      {definition.snapshotMode.toLowerCase()} · {definition.availableFormats.join(", ")}
                    </p>
                  </article>
                ))}
              </CardContent>
            </Card>
          </div>
        )}
      </FinanceBoundary>
    </div>
  );
}
