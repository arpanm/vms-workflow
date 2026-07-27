import { apiClient } from "@/lib/api-client";

import type {
  AccessEvent,
  CreateExportInput,
  CreateInvoiceInput,
  CreatePackageShareInput,
  ExportJob,
  FinanceAccessView,
  FinanceMonthSummary,
  FinanceMonthWorkspace,
  GeneratePackageInput,
  InvoiceSummary,
  InvoiceView,
  PackageDiff,
  PackageShare,
  PackageSummary,
  PackageView,
  Page,
  PaymentEvent,
  PaymentUpdateInput,
  ProcurementExceptionApprovalInput,
  ProcurementExceptionInput,
  ProcurementExceptionMutation,
  ProcurementQueryInput,
  ProcurementReviewInput,
  RevokePackageShareInput,
  SubmitInvoiceInput,
  UploadInvoiceDocumentInput,
} from "./contracts";
import {
  normalizeControlTower,
  normalizeDashboard,
  normalizeReports,
} from "./adapters";

const root = "/finance";
const encoded = (value: string) => encodeURIComponent(value);
const cursorQuery = (cursor?: string | null) => (cursor ? `?cursor=${encoded(cursor)}` : "");

function mutationHeaders(expectedVersion: number, idempotencyKey: string) {
  return {
    "If-Match": `"${expectedVersion}"`,
    "Idempotency-Key": idempotencyKey,
  };
}

function saveBlob({ blob, fileName }: { blob: Blob; fileName: string }) {
  const objectUrl = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = objectUrl;
  anchor.download = fileName;
  anchor.click();
  URL.revokeObjectURL(objectUrl);
}

export const financeApi = {
  access: () => apiClient.get<FinanceAccessView>(`${root}/access`),

  months: (cursor?: string | null) =>
    apiClient.get<Page<FinanceMonthSummary>>(`${root}/months${cursorQuery(cursor)}`),

  month: (monthId: string) =>
    apiClient.get<FinanceMonthWorkspace>(`${root}/months/${encoded(monthId)}`),

  invoices: (cursor?: string | null, monthId?: string | null) => {
    const query = new URLSearchParams();
    if (cursor) query.set("cursor", cursor);
    if (monthId) query.set("monthId", monthId);
    const suffix = query.size ? `?${query.toString()}` : "";
    return apiClient.get<Page<InvoiceSummary>>(`${root}/invoices${suffix}`);
  },

  createInvoice: (input: CreateInvoiceInput, idempotencyKey: string) =>
    apiClient.post<InvoiceView>(`${root}/invoices`, input, {
      headers: { "Idempotency-Key": idempotencyKey },
    }),

  invoice: (invoiceId: string) =>
    apiClient.get<InvoiceView>(`${root}/invoices/${encoded(invoiceId)}`),

  uploadInvoiceDocument: (
    invoiceId: string,
    input: UploadInvoiceDocumentInput,
    idempotencyKey: string,
    replace: boolean,
  ) => {
    const form = new FormData();
    form.set("file", input.file);
    form.set(
      "metadata",
      new Blob(
        [
          JSON.stringify({
            expectedVersion: input.expectedVersion,
            classification: input.classification,
            retentionPolicy: input.retentionPolicy,
            source: input.source,
            reason: input.reason,
          }),
        ],
        { type: "application/json" },
      ),
    );
    return apiClient.postForm<InvoiceView>(
      `${root}/invoices/${encoded(invoiceId)}/documents${replace ? "/replace" : ""}`,
      form,
      { headers: mutationHeaders(input.expectedVersion, idempotencyKey) },
    );
  },

  evaluateReadiness: (invoiceId: string, expectedVersion: number, idempotencyKey: string) =>
    apiClient.post<InvoiceView>(
      `${root}/invoices/${encoded(invoiceId)}/readiness-runs`,
      { expectedVersion },
      { headers: mutationHeaders(expectedVersion, idempotencyKey) },
    ),

  submitInvoice: (invoiceId: string, input: SubmitInvoiceInput, idempotencyKey: string) =>
    apiClient.post<InvoiceView>(`${root}/invoices/${encoded(invoiceId)}/submit`, input, {
      headers: mutationHeaders(input.expectedVersion, idempotencyKey),
    }),

  packageHistory: (monthId: string, cursor?: string | null) =>
    apiClient.get<Page<PackageSummary>>(
      `${root}/months/${encoded(monthId)}/packages${cursorQuery(cursor)}`,
    ),

  generatePackage: (monthId: string, input: GeneratePackageInput, idempotencyKey: string) =>
    apiClient.post<PackageSummary>(`${root}/months/${encoded(monthId)}/packages`, input, {
      headers: mutationHeaders(input.expectedMonthVersion, idempotencyKey),
    }),

  package: (packageId: string) =>
    apiClient.get<PackageView>(`${root}/packages/${encoded(packageId)}`),

  packageDiff: (packageId: string, againstPackageId: string) =>
    apiClient.get<PackageDiff>(
      `${root}/packages/${encoded(packageId)}/diff?against=${encoded(againstPackageId)}`,
    ),

  packageAccess: (packageId: string, cursor?: string | null) =>
    apiClient.get<Page<AccessEvent>>(
      `${root}/packages/${encoded(packageId)}/access-events${cursorQuery(cursor)}`,
    ),

  packageShares: (packageId: string, cursor?: string | null) =>
    apiClient.get<Page<PackageShare>>(
      `${root}/packages/${encoded(packageId)}/shares${cursorQuery(cursor)}`,
    ),

  createPackageShare: (
    packageId: string,
    input: CreatePackageShareInput,
    idempotencyKey: string,
  ) =>
    apiClient.post<PackageShare>(`${root}/packages/${encoded(packageId)}/shares`, input, {
      headers: { "Idempotency-Key": idempotencyKey },
    }),

  revokePackageShare: (
    packageId: string,
    input: RevokePackageShareInput,
    idempotencyKey: string,
  ) =>
    apiClient.post<PackageShare>(
      `${root}/packages/${encoded(packageId)}/shares/${encoded(input.shareId)}/revoke`,
      { reason: input.reason },
      { headers: { "Idempotency-Key": idempotencyKey } },
    ),

  downloadPackageArtifact: async (packageId: string, artifactId: string) => {
    const result = await apiClient.download(
      `${root}/packages/${encoded(packageId)}/artifacts/${encoded(artifactId)}/download`,
      { method: "POST" },
    );
    saveBlob(result);
  },

  controlTower: (cursor?: string | null) =>
    apiClient.get<unknown>(`${root}/procurement/control-tower${cursorQuery(cursor)}`)
      .then(normalizeControlTower),

  reviewInvoice: (
    invoiceId: string,
    input: ProcurementReviewInput,
    idempotencyKey: string,
  ) =>
    apiClient.post<InvoiceView>(`${root}/procurement/invoices/${encoded(invoiceId)}/reviews`, input, {
      headers: mutationHeaders(input.expectedVersion, idempotencyKey),
    }),

  createQuery: (
    invoiceId: string,
    input: ProcurementQueryInput,
    idempotencyKey: string,
  ) =>
    apiClient.post<InvoiceView>(`${root}/procurement/invoices/${encoded(invoiceId)}/queries`, input, {
      headers: mutationHeaders(input.expectedVersion, idempotencyKey),
    }),

  requestException: (
    invoiceId: string,
    input: ProcurementExceptionInput,
    idempotencyKey: string,
  ) =>
    apiClient.post<ProcurementExceptionMutation>(
      `${root}/procurement/invoices/${encoded(invoiceId)}/exceptions`,
      input,
      { headers: mutationHeaders(input.expectedVersion, idempotencyKey) },
    ),

  approveException: (
    exceptionId: string,
    input: ProcurementExceptionApprovalInput,
    idempotencyKey: string,
  ) =>
    apiClient.post<ProcurementExceptionMutation>(
      `${root}/procurement/exceptions/${encoded(exceptionId)}/second-approval`,
      input,
      { headers: mutationHeaders(input.expectedVersion, idempotencyKey) },
    ),

  paymentHistory: (invoiceId: string) =>
    apiClient.get<PaymentEvent[]>(`${root}/invoices/${encoded(invoiceId)}/payments`),

  updatePayment: (invoiceId: string, input: PaymentUpdateInput, idempotencyKey: string) =>
    apiClient.post<InvoiceView>(`${root}/invoices/${encoded(invoiceId)}/payments`, input, {
      headers: mutationHeaders(input.expectedVersion, idempotencyKey),
    }),

  dashboard: () => apiClient.get<unknown>(`${root}/dashboard`).then(normalizeDashboard),

  reports: (cursor?: string | null) =>
    apiClient.get<unknown>(`${root}/reports${cursorQuery(cursor)}`).then(normalizeReports),

  createExport: (input: CreateExportInput, idempotencyKey: string) =>
    apiClient.post<ExportJob>(`${root}/exports`, input, {
      headers: { "Idempotency-Key": idempotencyKey },
    }),

  downloadExport: async (exportId: string) => {
    const result = await apiClient.download(`${root}/exports/${encoded(exportId)}/download`, {
      method: "POST",
    });
    saveBlob(result);
  },
};
