import { AlertTriangle, LoaderCircle } from "lucide-react";

import { StatusBadge } from "@/components/status-badge";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

import { useActiveScope } from "./scope-provider";

export function ActiveScopeSelector() {
  const scope = useActiveScope();

  if (!scope.enabled) {
    return null;
  }

  if (scope.loading) {
    return (
      <div
        className="hidden items-center gap-2 text-xs text-muted-foreground md:flex"
        role="status"
      >
        <LoaderCircle className="h-4 w-4 animate-spin" aria-hidden="true" />
        Loading scope…
      </div>
    );
  }

  if (scope.error) {
    return (
      <div className="hidden items-center gap-2 text-xs text-destructive md:flex" role="alert">
        <AlertTriangle className="h-4 w-4" aria-hidden="true" />
        Scope unavailable
      </div>
    );
  }

  if (!scope.organization) {
    return (
      <span className="hidden text-xs text-muted-foreground md:inline">No authorized scope</span>
    );
  }

  return (
    <div
      className="hidden items-center gap-2 md:flex"
      aria-label="Active organization, engagement and month"
    >
      <Select value={scope.selection.organizationId} onValueChange={scope.selectOrganization}>
        <SelectTrigger className="h-9 w-[180px]" aria-label="Active organization">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {scope.organizations.map((organization) => (
            <SelectItem key={organization.id} value={organization.id}>
              {organization.displayName}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <Select
        value={scope.selection.engagementId}
        onValueChange={scope.selectEngagement}
        disabled={!scope.engagements.length}
      >
        <SelectTrigger className="h-9 w-[210px]" aria-label="Active engagement">
          <SelectValue placeholder="No engagement" />
        </SelectTrigger>
        <SelectContent>
          {scope.engagements.map((engagement) => (
            <SelectItem key={engagement.id} value={engagement.id}>
              {engagement.name}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <Select
        value={scope.selection.monthId}
        onValueChange={scope.selectMonth}
        disabled={!scope.months.length}
      >
        <SelectTrigger className="h-9 w-[170px]" aria-label="Active month">
          <SelectValue placeholder="No month" />
        </SelectTrigger>
        <SelectContent>
          {scope.months.map((month) => (
            <SelectItem key={month.id} value={month.id}>
              {month.monthStartDate}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      {scope.month ? <StatusBadge status={scope.month.state} /> : null}
    </div>
  );
}
