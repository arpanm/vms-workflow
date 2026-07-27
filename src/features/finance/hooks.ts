import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef } from "react";

import { financeApi } from "./api";
import type {
  CreateExportInput,
  CreateInvoiceInput,
  CreatePackageShareInput,
  GeneratePackageInput,
  PaymentUpdateInput,
  ProcurementExceptionApprovalInput,
  ProcurementExceptionInput,
  ProcurementQueryInput,
  ProcurementReviewInput,
  RevokePackageShareInput,
  SubmitInvoiceInput,
  UploadInvoiceDocumentInput,
} from "./contracts";
import { FinanceMutationIntentStore } from "./idempotency";

export const financeKeys = {
  all: ["finance"] as const,
  access: ["finance", "access"] as const,
  months: (cursor?: string | null) => ["finance", "months", cursor ?? "first"] as const,
  month: (monthId: string) => ["finance", "month", monthId] as const,
  invoices: (cursor?: string | null) => ["finance", "invoices", cursor ?? "first"] as const,
  invoice: (invoiceId: string) => ["finance", "invoice", invoiceId] as const,
  package: (packageId: string) => ["finance", "package", packageId] as const,
  packageHistory: (monthId: string, cursor?: string | null) =>
    ["finance", "package-history", monthId, cursor ?? "first"] as const,
  packageDiff: (packageId: string, againstId: string) =>
    ["finance", "package-diff", packageId, againstId] as const,
  packageAccess: (packageId: string, cursor?: string | null) =>
    ["finance", "package-access", packageId, cursor ?? "first"] as const,
  packageShares: (packageId: string, cursor?: string | null) =>
    ["finance", "package-shares", packageId, cursor ?? "first"] as const,
  controlTower: (cursor?: string | null) =>
    ["finance", "control-tower", cursor ?? "first"] as const,
  dashboard: ["finance", "dashboard"] as const,
  reports: (cursor?: string | null) => ["finance", "reports", cursor ?? "first"] as const,
};

function useIntentMutation<TInput, TResult>({
  mutationFn,
  onSuccess,
  onError,
}: {
  mutationFn: (input: TInput, idempotencyKey: string) => Promise<TResult>;
  onSuccess?: (result: TResult) => unknown;
  onError?: (error: Error) => unknown;
}) {
  const intent = useRef(new FinanceMutationIntentStore<TInput>());
  return useMutation({
    mutationFn: (input: TInput) => mutationFn(input, intent.current.acquire(input).idempotencyKey),
    onSuccess: (result) => {
      intent.current.settle();
      return onSuccess?.(result);
    },
    onError: (error) => {
      intent.current.settle(error);
      return onError?.(error);
    },
  });
}

export function useFinanceAccess() {
  return useQuery({ queryKey: financeKeys.access, queryFn: financeApi.access });
}

export function useFinanceMonths(cursor?: string | null) {
  return useQuery({
    queryKey: financeKeys.months(cursor),
    queryFn: () => financeApi.months(cursor),
  });
}

export function useFinanceMonth(monthId: string) {
  return useQuery({
    queryKey: financeKeys.month(monthId),
    queryFn: () => financeApi.month(monthId),
    enabled: Boolean(monthId),
  });
}

export function useFinanceInvoices(cursor?: string | null) {
  return useQuery({
    queryKey: financeKeys.invoices(cursor),
    queryFn: () => financeApi.invoices(cursor),
  });
}

export function useFinanceInvoice(invoiceId: string) {
  return useQuery({
    queryKey: financeKeys.invoice(invoiceId),
    queryFn: () => financeApi.invoice(invoiceId),
    enabled: Boolean(invoiceId),
  });
}

export function useCreateInvoice() {
  const queryClient = useQueryClient();
  return useIntentMutation({
    mutationFn: (input: CreateInvoiceInput, key) => financeApi.createInvoice(input, key),
    onSuccess: (invoice) => {
      queryClient.setQueryData(financeKeys.invoice(invoice.invoiceId), invoice);
      void queryClient.invalidateQueries({ queryKey: ["finance", "invoices"] });
      return queryClient.invalidateQueries({ queryKey: financeKeys.month(invoice.monthId) });
    },
  });
}

function useInvoiceMutation<TInput>(
  invoiceId: string,
  mutationFn: (input: TInput, key: string) => ReturnType<typeof financeApi.submitInvoice>,
) {
  const queryClient = useQueryClient();
  return useIntentMutation({
    mutationFn,
    onSuccess: (invoice) => {
      queryClient.setQueryData(financeKeys.invoice(invoiceId), invoice);
      void queryClient.invalidateQueries({ queryKey: ["finance", "invoices"] });
      void queryClient.invalidateQueries({ queryKey: financeKeys.month(invoice.monthId) });
      void queryClient.invalidateQueries({ queryKey: ["finance", "control-tower"] });
      return queryClient.invalidateQueries({ queryKey: financeKeys.dashboard });
    },
    onError: () => queryClient.invalidateQueries({ queryKey: financeKeys.invoice(invoiceId) }),
  });
}

export function useUploadInvoiceDocument(invoiceId: string, replace: boolean) {
  return useInvoiceMutation(invoiceId, (input: UploadInvoiceDocumentInput, key) =>
    financeApi.uploadInvoiceDocument(invoiceId, input, key, replace),
  );
}

export function useEvaluateReadiness(invoiceId: string) {
  return useInvoiceMutation(
    invoiceId,
    ({ expectedVersion }: { expectedVersion: number }, key) =>
      financeApi.evaluateReadiness(invoiceId, expectedVersion, key),
  );
}

export function useSubmitInvoice(invoiceId: string) {
  return useInvoiceMutation(invoiceId, (input: SubmitInvoiceInput, key) =>
    financeApi.submitInvoice(invoiceId, input, key),
  );
}

export function usePackage(packageId: string) {
  return useQuery({
    queryKey: financeKeys.package(packageId),
    queryFn: () => financeApi.package(packageId),
    enabled: Boolean(packageId),
  });
}

export function usePackageHistory(monthId: string, cursor?: string | null) {
  return useQuery({
    queryKey: financeKeys.packageHistory(monthId, cursor),
    queryFn: () => financeApi.packageHistory(monthId, cursor),
    enabled: Boolean(monthId),
  });
}

export function usePackageDiff(packageId: string, againstId: string) {
  return useQuery({
    queryKey: financeKeys.packageDiff(packageId, againstId),
    queryFn: () => financeApi.packageDiff(packageId, againstId),
    enabled: Boolean(packageId && againstId),
  });
}

export function usePackageAccess(
  packageId: string,
  enabled: boolean,
  cursor?: string | null,
) {
  return useQuery({
    queryKey: financeKeys.packageAccess(packageId, cursor),
    queryFn: () => financeApi.packageAccess(packageId, cursor),
    enabled: Boolean(packageId) && enabled,
  });
}

export function usePackageShares(
  packageId: string,
  enabled: boolean,
  cursor?: string | null,
) {
  return useQuery({
    queryKey: financeKeys.packageShares(packageId, cursor),
    queryFn: () => financeApi.packageShares(packageId, cursor),
    enabled: Boolean(packageId) && enabled,
  });
}

export function useCreatePackageShare(packageId: string) {
  const queryClient = useQueryClient();
  return useIntentMutation({
    mutationFn: (input: CreatePackageShareInput, key) =>
      financeApi.createPackageShare(packageId, input, key),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: ["finance", "package-shares", packageId],
      });
      return queryClient.invalidateQueries({
        queryKey: ["finance", "package-access", packageId],
      });
    },
  });
}

export function useRevokePackageShare(packageId: string) {
  const queryClient = useQueryClient();
  return useIntentMutation({
    mutationFn: (input: RevokePackageShareInput, key) =>
      financeApi.revokePackageShare(packageId, input, key),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: ["finance", "package-shares", packageId],
      });
      return queryClient.invalidateQueries({
        queryKey: ["finance", "package-access", packageId],
      });
    },
  });
}

export function useGeneratePackage(monthId: string) {
  const queryClient = useQueryClient();
  return useIntentMutation({
    mutationFn: (input: GeneratePackageInput, key) =>
      financeApi.generatePackage(monthId, input, key),
    onSuccess: (result) => {
      void queryClient.invalidateQueries({
        queryKey: financeKeys.package(result.packageId),
      });
      void queryClient.invalidateQueries({
        queryKey: ["finance", "package-history", monthId],
      });
      return queryClient.invalidateQueries({ queryKey: financeKeys.month(monthId) });
    },
  });
}

export function usePackageDownload() {
  return useMutation({
    mutationFn: ({ packageId, artifactId }: { packageId: string; artifactId: string }) =>
      financeApi.downloadPackageArtifact(packageId, artifactId),
  });
}

export function useControlTower(cursor?: string | null) {
  return useQuery({
    queryKey: financeKeys.controlTower(cursor),
    queryFn: () => financeApi.controlTower(cursor),
  });
}

export function useProcurementReview(invoiceId: string) {
  return useInvoiceMutation(invoiceId, (input: ProcurementReviewInput, key) =>
    financeApi.reviewInvoice(invoiceId, input, key),
  );
}

export function useProcurementQuery(invoiceId: string) {
  return useInvoiceMutation(invoiceId, (input: ProcurementQueryInput, key) =>
    financeApi.createQuery(invoiceId, input, key),
  );
}

function useProcurementExceptionMutation<TInput>(
  invoiceId: string,
  mutationFn: (
    input: TInput,
    key: string,
  ) => ReturnType<typeof financeApi.requestException>,
) {
  const queryClient = useQueryClient();
  return useIntentMutation({
    mutationFn,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["finance", "invoices"] });
      void queryClient.invalidateQueries({ queryKey: ["finance", "control-tower"] });
      void queryClient.invalidateQueries({ queryKey: financeKeys.dashboard });
      return queryClient.invalidateQueries({ queryKey: financeKeys.invoice(invoiceId) });
    },
    onError: () => queryClient.invalidateQueries({ queryKey: financeKeys.invoice(invoiceId) }),
  });
}

export function useProcurementExceptionRequest(invoiceId: string) {
  return useProcurementExceptionMutation(
    invoiceId,
    (input: ProcurementExceptionInput, key) =>
      financeApi.requestException(invoiceId, input, key),
  );
}

export function useProcurementExceptionApproval(invoiceId: string, exceptionId: string) {
  return useProcurementExceptionMutation(
    invoiceId,
    (input: ProcurementExceptionApprovalInput, key) =>
      financeApi.approveException(exceptionId, input, key),
  );
}

export function usePaymentUpdate(invoiceId: string) {
  return useInvoiceMutation(invoiceId, (input: PaymentUpdateInput, key) =>
    financeApi.updatePayment(invoiceId, input, key),
  );
}

export function useFinanceDashboard() {
  return useQuery({ queryKey: financeKeys.dashboard, queryFn: financeApi.dashboard });
}

export function useReports(cursor?: string | null) {
  return useQuery({
    queryKey: financeKeys.reports(cursor),
    queryFn: () => financeApi.reports(cursor),
  });
}

export function useCreateExport() {
  const queryClient = useQueryClient();
  return useIntentMutation({
    mutationFn: (input: CreateExportInput, key) => financeApi.createExport(input, key),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["finance", "reports"] }),
  });
}

export function useExportDownload() {
  return useMutation({ mutationFn: financeApi.downloadExport });
}
