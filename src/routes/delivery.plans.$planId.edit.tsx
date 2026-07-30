import { createFileRoute } from "@tanstack/react-router";

import { PageHeader } from "@/components/page-header";
import { DeliveryQueryBoundary } from "@/features/delivery/query-boundary";
import { usePlan } from "@/features/delivery/hooks";
import { PlanBuilder } from "@/routes/delivery.plans.new";
import { requireDeliveryRoute } from "@/lib/delivery-route";

export const Route = createFileRoute("/delivery/plans/$planId/edit")({
  beforeLoad: requireDeliveryRoute,
  head: () => ({ meta: [{ title: "Edit delivery plan — Cadence" }] }),
  component: EditPlanPage,
});

function EditPlanPage() {
  const { planId } = Route.useParams();
  const query = usePlan(planId);
  const plan = query.data;
  return (
    <div>
      <PageHeader
        title={plan ? `Edit ${plan.title}` : "Edit delivery plan"}
        description="Change only the exact current draft. A frozen commitment must first be cloned into a reasoned revision."
      />
      <div className="p-6">
        <DeliveryQueryBoundary queries={[query]}>
          {plan?.state === "DRAFT" ? (
            <PlanBuilder engagementMonthId={plan.engagementMonthId} initialPlan={plan} />
          ) : plan ? (
            <p role="alert">
              This version is {plan.state.toLowerCase()} and immutable. Create a revision before
              editing commitment content.
            </p>
          ) : null}
        </DeliveryQueryBoundary>
      </div>
    </div>
  );
}
