import { createFileRoute } from "@tanstack/react-router";
import { AlertTriangle, Clock3, Link2, ListRestart, ShieldAlert } from "lucide-react";

import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { useLinearHealth } from "@/features/delivery/hooks";
import { DeliveryQueryBoundary } from "@/features/delivery/query-boundary";
import { DeliveryEngagementScope } from "@/features/delivery/scope";
import {
  connectionStatusPresentation,
  providerRegistrationPresentation,
} from "@/features/delivery/presentation";
import { requireDeliveryRoute } from "@/lib/delivery-route";

export const Route = createFileRoute("/delivery/integration-health")({
  beforeLoad: requireDeliveryRoute,
  head: () => ({ meta: [{ title: "Linear integration health — Cadence" }] }),
  component: IntegrationHealthPage,
});

function IntegrationHealthPage() {
  return (
    <div>
      <PageHeader
        title="Linear integration health"
        description="Provider readiness, freshness and durable queue visibility without exposing credentials or triggering page-load polling."
      />
      <div className="p-6">
        <DeliveryEngagementScope>
          {(engagementId) => <Health engagementId={engagementId} />}
        </DeliveryEngagementScope>
      </div>
    </div>
  );
}

function Health({ engagementId }: { engagementId: string }) {
  const query = useLinearHealth(engagementId);
  const health = query.data;
  return (
    <DeliveryQueryBoundary queries={[query]}>
      {health && (
        <div className="space-y-4">
          {health.status !== "CONNECTED" && (
            <Card className="border-warning/40 bg-warning/5">
              <CardContent className="flex gap-3 py-4 text-sm">
                <ShieldAlert className="h-5 w-5 shrink-0 text-warning" />
                <div>
                  <p className="font-medium">
                    Provider is {health.status.toLowerCase().replace("_", " ")}
                  </p>
                  <p className="mt-1 text-muted-foreground">
                    {connectionStatusPresentation(health.status)}
                  </p>
                </div>
              </CardContent>
            </Card>
          )}
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
            <HealthCard
              icon={Link2}
              label="Connection"
              value={<StatusBadge status={health.status} />}
              detail={`${providerRegistrationPresentation(
                health.providerRegistrationStatus,
              )}. ${connectionStatusPresentation(health.status)}`}
            />
            <HealthCard
              icon={Clock3}
              label="Last reconciled"
              value={health.lastReconciledAt ?? "Never"}
              detail={`Last verified webhook: ${health.lastVerifiedDeliveryAt ?? "Never"}`}
            />
            <HealthCard
              icon={AlertTriangle}
              label="Stale issues"
              value={health.staleIssueCount}
              detail={`${health.linkedIssueCount} linked`}
            />
            <HealthCard
              icon={ListRestart}
              label="Queue / dead-letter"
              value={`${health.queuedCount} / ${health.deadLetterCount}`}
              detail="Operator review required"
            />
          </div>
          {health.lastError && (
            <Card className="border-destructive/30">
              <CardHeader>
                <CardTitle className="text-base">Last sanitized error</CardTitle>
              </CardHeader>
              <CardContent className="text-sm text-muted-foreground">
                {health.lastError}
              </CardContent>
            </Card>
          )}
          <p className="text-xs text-muted-foreground">
            The backend supports a bounded delivery processing control for authorized operators. It
            is intentionally not exposed here because the health contract does not provide per-user
            replay authorization or a safe dead-letter delivery identifier.
          </p>
        </div>
      )}
    </DeliveryQueryBoundary>
  );
}

function HealthCard({
  icon: Icon,
  label,
  value,
  detail,
}: {
  icon: typeof Link2;
  label: string;
  value: React.ReactNode;
  detail: string;
}) {
  return (
    <Card>
      <CardContent className="p-4">
        <Icon className="h-4 w-4 text-muted-foreground" />
        <p className="mt-3 text-xs uppercase tracking-wide text-muted-foreground">{label}</p>
        <div className="mt-1 font-semibold">{value}</div>
        <p className="mt-1 text-xs text-muted-foreground">{detail}</p>
      </CardContent>
    </Card>
  );
}
