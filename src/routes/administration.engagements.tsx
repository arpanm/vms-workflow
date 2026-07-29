import { createFileRoute } from "@tanstack/react-router";
import { Building2, Save } from "lucide-react";
import { useState } from "react";

import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  CoreAdminBoundary,
  MutationNotice,
} from "@/features/core-admin/components";
import { ConfigurationPanel } from "@/features/core-admin/configuration-panel";
import type { EngagementAdministrationView } from "@/features/core-admin/contracts";
import {
  useConfigurations,
  useCoreEngagement,
  useUpdateEngagement,
} from "@/features/core-admin/hooks";
import { coreAdminPermissions } from "@/features/core-admin/permissions";
import { useActiveScope } from "@/features/core-admin/scope-provider";

export const Route = createFileRoute("/administration/engagements")({
  head: () => ({
    meta: [
      { title: "Engagement administration — Cadence" },
      {
        name: "description",
        content:
          "Authorized engagement master data and effective configuration reference.",
      },
    ],
  }),
  component: EngagementAdministrationPage,
});

function EngagementAdministrationPage() {
  const scope = useActiveScope();
  const readable = scope.can(coreAdminPermissions.read);
  const query = useCoreEngagement(scope.selection.engagementId, readable);
  const configurations = useConfigurations(
    scope.selection.engagementId,
    readable,
  );
  const boundaryQuery =
    query.isPending || query.isError ? query : configurations;

  return (
    <div>
      <PageHeader
        title="Organizations & engagements"
        description="The active organization and engagement come from your server-authorized scope. Changes use optimistic version checks."
      />
      <CoreAdminBoundary
        authorized={readable}
        query={boundaryQuery}
        empty={!scope.selection.engagementId}
        emptyTitle="No engagement assigned"
        emptyDescription="Choose an authorized organization with an engagement, or ask an administrator for scoped access."
      >
        {query.data ? (
          <EngagementEditor
            key={`${query.data.id}:${query.data.version}`}
            engagement={query.data}
            editable={scope.can(coreAdminPermissions.engagementUpdate)}
            configurationEditable={scope.can(
              coreAdminPermissions.engagementConfigure,
            )}
            configurations={configurations.data ?? []}
            onReload={() => void query.refetch()}
          />
        ) : null}
      </CoreAdminBoundary>
    </div>
  );
}

function EngagementEditor({
  engagement,
  editable,
  configurationEditable,
  configurations,
  onReload,
}: {
  engagement: EngagementAdministrationView;
  editable: boolean;
  configurationEditable: boolean;
  configurations: import("@/features/core-admin/contracts").ConfigurationView[];
  onReload: () => void;
}) {
  const mutation = useUpdateEngagement(engagement.id);
  const [name, setName] = useState(engagement.name);
  const [status, setStatus] = useState(engagement.status);

  function submit(event: React.FormEvent) {
    event.preventDefault();
    mutation.mutate({
      name: name.trim(),
      status,
      defaultProjectId: engagement.defaultProjectId ?? null,
      expectedVersion: engagement.version,
    });
  }

  return (
    <div className="space-y-5 p-6">
      <div className="grid gap-5 xl:grid-cols-[2fr_1fr]">
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <Building2 className="h-4 w-4" aria-hidden="true" />
            Engagement master
          </CardTitle>
        </CardHeader>
        <CardContent>
          <form className="space-y-4" onSubmit={submit}>
            <div className="grid gap-4 md:grid-cols-2">
              <Field label="Engagement code" value={engagement.engagementCode} />
              <div className="md:col-span-2">
                <Label htmlFor="engagement-name">Display name</Label>
                <Input
                  id="engagement-name"
                  value={name}
                  onChange={(event) => setName(event.target.value)}
                  disabled={!editable}
                  required
                />
              </div>
              <div>
                <Label htmlFor="engagement-status">Status</Label>
                <Select
                  value={status}
                  onValueChange={setStatus}
                  disabled={!editable}
                >
                  <SelectTrigger id="engagement-status">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {["DRAFT", "ACTIVE", "SUSPENDED", "COMPLETED", "ARCHIVED"].map(
                      (value) => (
                        <SelectItem key={value} value={value}>
                          {value}
                        </SelectItem>
                      ),
                    )}
                  </SelectContent>
                </Select>
              </div>
            </div>
            <MutationNotice
              error={mutation.error}
              pending={mutation.isPending}
              onReload={onReload}
            />
            {editable ? (
              <Button
                type="submit"
                disabled={mutation.isPending || !name.trim()}
              >
                <Save className="mr-2 h-4 w-4" aria-hidden="true" />
                Save version {engagement.version + 1}
              </Button>
            ) : (
              <p className="text-sm text-muted-foreground">
                Read-only: engagement.update is not granted in this scope.
              </p>
            )}
          </form>
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Authority-safe context</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3 text-sm">
          <div className="flex items-center justify-between gap-4">
            <span className="text-muted-foreground">Status</span>
            <StatusBadge status={engagement.status} />
          </div>
          <Field
            label="Configuration version"
            value={engagement.configurationVersionId ?? "Not published"}
          />
          <Field
            label="Server administration version"
            value={String(engagement.version)}
          />
          <p className="border-t pt-3 text-xs text-muted-foreground">
            Client, vendor and procurement organization IDs are immutable on this
            surface to prevent accidental tenant-boundary changes.
          </p>
        </CardContent>
      </Card>
      </div>
      <ConfigurationPanel
        engagement={engagement}
        configurations={configurations}
        editable={configurationEditable}
        onReload={onReload}
      />
    </div>
  );
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="break-words font-medium">{value || "—"}</p>
    </div>
  );
}
