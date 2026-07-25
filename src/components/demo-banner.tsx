import { TriangleAlert } from "lucide-react";

import { safeDemoMode } from "@/lib/feature-flags";

export function DemoBanner() {
  if (!safeDemoMode) return null;

  return (
    <div
      className="flex min-h-9 items-center justify-center gap-2 bg-warning/25 px-4 py-2 text-center text-xs font-medium text-warning-foreground"
      role="status"
    >
      <TriangleAlert className="h-4 w-4" aria-hidden="true" />
      Demo mode: persona switching changes presentation only and never grants
      backend permissions.
    </div>
  );
}
