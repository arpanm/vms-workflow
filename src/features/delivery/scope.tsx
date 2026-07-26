import { useState } from "react";

import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  useCatalogEngagements,
  useOrganizations,
} from "@/features/workforce/hooks";

import { DeliveryQueryBoundary } from "./query-boundary";

export function DeliveryEngagementScope({
  children,
}: {
  children: (engagementId: string) => React.ReactNode;
}) {
  const organizationsQuery = useOrganizations();
  const organizations = organizationsQuery.data ?? [];
  const [organizationId, setOrganizationId] = useState("");
  const resolvedOrganization = organizationId || organizations[0]?.id || "";
  const engagementsQuery = useCatalogEngagements(resolvedOrganization);
  const engagements = engagementsQuery.data ?? [];
  const [engagementId, setEngagementId] = useState("");
  const resolvedEngagement = engagements.some(
    (engagement) => engagement.id === engagementId,
  )
    ? engagementId
    : engagements[0]?.id || "";

  return (
    <DeliveryQueryBoundary queries={[organizationsQuery, engagementsQuery]}>
      {!resolvedOrganization || !resolvedEngagement ? (
        <div className="rounded-lg border border-dashed p-10 text-center text-sm text-muted-foreground">
          No authorized engagement is available.
        </div>
      ) : (
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-2">
            <Select
              value={resolvedOrganization}
              onValueChange={(value) => {
                setOrganizationId(value);
                setEngagementId("");
              }}
            >
              <SelectTrigger aria-label="Organization">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {organizations.map((item) => (
                  <SelectItem value={item.id} key={item.id}>
                    {item.displayName}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Select
              value={resolvedEngagement}
              onValueChange={setEngagementId}
            >
              <SelectTrigger aria-label="Engagement">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {engagements.map((item) => (
                  <SelectItem value={item.id} key={item.id}>
                    {item.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          {children(resolvedEngagement)}
        </div>
      )}
    </DeliveryQueryBoundary>
  );
}
