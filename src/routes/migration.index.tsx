import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";

import { MigrationWorkspace } from "@/features/migration/workspace";

export const Route = createFileRoute("/migration/")({
  validateSearch: z.object({ jobId: z.string().optional() }),
  head: () => ({
    meta: [
      { title: "Historical migration center — Cadence" },
      {
        name: "description",
        content: "Governed staged validation, reconciliation and historical migration.",
      },
    ],
  }),
  component: MigrationRoute,
});

function MigrationRoute() {
  return <MigrationWorkspace selectedJobId={Route.useSearch().jobId} />;
}
