import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";

import { ProcurementWorkspace } from "@/features/finance/procurement-workspace";

const searchSchema = z.object({ invoiceId: z.string().trim().min(1).optional() });

export const Route = createFileRoute("/finance/procurement")({
  validateSearch: searchSchema,
  head: () => ({ meta: [{ title: "Procurement control tower — Cadence" }] }),
  component: ProcurementRoute,
});

function ProcurementRoute() {
  return <ProcurementWorkspace invoiceId={Route.useSearch().invoiceId} />;
}
