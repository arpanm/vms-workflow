import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";

import { FinanceWorkspace } from "@/features/finance/workspace";

const searchSchema = z.object({
  monthId: z.string().trim().min(1).optional(),
  invoiceId: z.string().trim().min(1).optional(),
  packageId: z.string().trim().min(1).optional(),
});

export const Route = createFileRoute("/finance/")({
  validateSearch: searchSchema,
  head: () => ({
    meta: [
      { title: "Finance evidence workspace — Cadence" },
      {
        name: "description",
        content: "Scoped invoice readiness, evidence package and payment workspace.",
      },
    ],
  }),
  component: FinanceWorkspaceRoute,
});

function FinanceWorkspaceRoute() {
  return <FinanceWorkspace search={Route.useSearch()} />;
}
