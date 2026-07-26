import { Link, createFileRoute } from "@tanstack/react-router";
import { FilePlus2, ListChecks } from "lucide-react";

import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Progress } from "@/components/ui/progress";
import { usePlans } from "@/features/delivery/hooks";
import { DeliveryQueryBoundary } from "@/features/delivery/query-boundary";
import { MonthScope } from "@/features/workforce/scope-selectors";
import { requireDeliveryRoute } from "@/lib/delivery-route";

export const Route = createFileRoute("/delivery/plans/")({
  beforeLoad: requireDeliveryRoute,
  head: () => ({ meta: [{ title: "Delivery plans — Cadence" }] }),
  component: PlansPage,
});

function PlansPage() {
  return (
    <div>
      <PageHeader
        title="Monthly delivery plans"
        description="Immutable versioned commitments, approval coverage and baseline status by engagement month."
      >
        <Button asChild>
          <Link to="/delivery/plans/new">
            <FilePlus2 className="mr-2 h-4 w-4" />
            Create plan
          </Link>
        </Button>
      </PageHeader>
      <div className="p-6">
        <MonthScope>
          {(engagementMonthId) => (
            <PlanList engagementMonthId={engagementMonthId} />
          )}
        </MonthScope>
      </div>
    </div>
  );
}
function PlanList({ engagementMonthId }: { engagementMonthId: string }) {
  const query = usePlans(engagementMonthId);
  const plans = query.data ?? [];
  return (
    <DeliveryQueryBoundary queries={[query]}>
      {plans.length === 0 ? (
        <Card className="border-dashed">
          <CardContent className="grid min-h-52 place-items-center text-center">
            <div>
              <ListChecks className="mx-auto h-8 w-8 text-muted-foreground" />
              <p className="mt-3 font-medium">No plan versions found</p>
              <p className="mt-1 text-sm text-muted-foreground">
                Create the first delivery plan for this engagement month.
              </p>
            </div>
          </CardContent>
        </Card>
      ) : (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {plans.map((plan) => {
            const approvalPercent = plan.requiredApprovals
              ? Math.round(
                  (plan.approvedCount / plan.requiredApprovals) * 100,
                )
              : 0;
            return (
              <Card key={plan.id}>
                <CardContent className="space-y-4 p-5">
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <Link
                        to="/delivery/plans/$planId"
                        params={{ planId: plan.id }}
                        className="font-semibold text-primary hover:underline"
                      >
                        {plan.title}
                      </Link>
                      <p className="mt-1 text-xs text-muted-foreground">
                        Version {plan.version} · {plan.baselineType}
                      </p>
                    </div>
                    <StatusBadge status={plan.state} />
                  </div>
                  <div>
                    <div className="flex justify-between text-xs">
                      <span>Approval coverage</span>
                      <span>
                        {plan.approvedCount}/{plan.requiredApprovals}
                      </span>
                    </div>
                    <Progress value={approvalPercent} className="mt-2 h-1.5" />
                  </div>
                  <div className="grid grid-cols-2 gap-3 text-xs">
                    <Metric
                      label="Deliverables"
                      value={plan.deliverableCount}
                    />
                    <Metric
                      label="Frozen"
                      value={plan.frozenAt ?? "Not frozen"}
                    />
                  </div>
                </CardContent>
              </Card>
            );
          })}
        </div>
      )}
    </DeliveryQueryBoundary>
  );
}

function Metric({ label, value }: { label: string; value: string | number }) {
  return (
    <div>
      <p className="uppercase tracking-wide text-muted-foreground">{label}</p>
      <p className="mt-1 font-medium">{value}</p>
    </div>
  );
}
