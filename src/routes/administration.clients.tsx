import { createFileRoute } from "@tanstack/react-router";

import { ClientAdministrationWorkspace } from "@/features/collaboration/client-administration";

export const Route = createFileRoute("/administration/clients")({
  head: () => ({ meta: [{ title: "Client onboarding — Cadence" }] }),
  component: ClientAdministrationWorkspace,
});
