import { Navigate, createFileRoute } from "@tanstack/react-router";

export const Route = createFileRoute("/invoices")({
  head: () => ({ meta: [{ title: "Finance workspace — Cadence" }] }),
  component: LegacyInvoiceRedirect,
});

function LegacyInvoiceRedirect() {
  return (
    <Navigate
      to="/finance"
      search={{ monthId: undefined, invoiceId: undefined, packageId: undefined }}
      replace
    />
  );
}
