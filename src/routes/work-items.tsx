import { createFileRoute } from "@tanstack/react-router";

import { CollaborationWorkspace } from "@/features/collaboration/workspace";

export const Route = createFileRoute("/work-items")({
  head: () => ({ meta: [{ title: "Client work items — Cadence" }] }),
  component: CollaborationWorkspace,
});
