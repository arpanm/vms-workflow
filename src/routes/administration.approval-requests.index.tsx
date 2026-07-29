import { Link, createFileRoute } from "@tanstack/react-router";
import { ClipboardList, Plus } from "lucide-react";
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
import {
  useApprovalPolicies,
  useApprovalRequests,
  useCreateApprovalRequest,
} from "@/features/core-admin/hooks";
import { coreAdminPermissions } from "@/features/core-admin/permissions";
import { useActiveScope } from "@/features/core-admin/scope-provider";

export const Route = createFileRoute("/administration/approval-requests/")({
  head: () => ({
    meta: [
      { title: "Approval inbox — Cadence" },
      {
        name: "description",
        content:
          "Scoped approval requests, policy stages, quorum progress and attributable actions.",
      },
    ],
  }),
  component: ApprovalInboxPage,
});

function ApprovalInboxPage() {
  const scope = useActiveScope();
  const engagementId = scope.selection.engagementId;
  const readable = scope.can(coreAdminPermissions.approvalRequestRead);
  const canCreate = scope.can(coreAdminPermissions.approvalRequestCreate);
  const requests = useApprovalRequests(engagementId, readable);
  const policies = useApprovalPolicies(engagementId, canCreate);

  return (
    <div>
      <PageHeader
        title="Approval inbox"
        description="Requests bind a published policy version to an exact object ID, version and SHA-256 hash. Silence never changes status."
      />
      <CoreAdminBoundary
        authorized={readable}
        query={requests}
        empty={!engagementId}
        emptyTitle="No engagement selected"
        emptyDescription="Select an authorized engagement before opening its approval inbox."
      >
        <div className="space-y-5 p-6">
          {canCreate ? (
            <CoreAdminBoundary query={policies}>
              <CreateApprovalRequestForm
                engagementId={engagementId}
                policies={(policies.data ?? []).filter(
                  (policy) =>
                    policy.versionStatus === "PUBLISHED" &&
                    policy.actionType === "REOPEN" &&
                    !policy.projectId,
                )}
              />
            </CoreAdminBoundary>
          ) : null}
          {requests.data?.length ? (
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2 text-base">
                  <ClipboardList className="h-4 w-4" aria-hidden="true" />
                  Governed requests
                </CardTitle>
              </CardHeader>
              <CardContent className="p-0">
                <ul className="divide-y">
                  {requests.data.map((request) => (
                    <li
                      key={request.id}
                      className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center"
                    >
                      <div className="min-w-0 flex-1">
                        <Link
                          to="/administration/approval-requests/$requestId"
                          params={{ requestId: request.id }}
                          className="font-medium text-primary hover:underline"
                        >
                          {request.objectType} · {request.objectId}
                        </Link>
                        <p className="mt-1 text-xs text-muted-foreground">
                          Object version {request.objectVersion} · request version{" "}
                          {request.version} · stage {request.currentStageOrder} of{" "}
                          {request.stages.length}
                        </p>
                        <p className="mt-1 truncate font-mono text-[11px] text-muted-foreground">
                          {request.objectHash}
                        </p>
                      </div>
                      <div className="flex items-center gap-3">
                        <span className="text-xs text-muted-foreground">
                          {new Date(request.requestedAt).toLocaleString()}
                        </span>
                        <StatusBadge status={request.status} />
                      </div>
                    </li>
                  ))}
                </ul>
              </CardContent>
            </Card>
          ) : (
            <CoreAdminBoundary
              empty
              emptyTitle="No approval requests"
              emptyDescription="There are no pending or historical governed requests in this engagement."
            >
              {null}
            </CoreAdminBoundary>
          )}
        </div>
      </CoreAdminBoundary>
    </div>
  );
}

function CreateApprovalRequestForm({
  engagementId,
  policies,
}: {
  engagementId: string;
  policies: Array<{
    id: string;
    name: string;
    actionType: string;
    projectId?: string | null;
  }>;
}) {
  const mutation = useCreateApprovalRequest(engagementId);
  const [policyId, setPolicyId] = useState("");
  const [objectId, setObjectId] = useState("");
  const [idempotencyKey, setIdempotencyKey] = useState(() =>
    crypto.randomUUID(),
  );
  function submit(event: React.FormEvent) {
    event.preventDefault();
    mutation.mutate(
      {
        policyId,
        objectId: objectId.trim(),
        idempotencyKey,
      },
      {
        onSuccess: () => {
          setObjectId("");
          setIdempotencyKey(crypto.randomUUID());
        },
      },
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <Plus className="h-4 w-4" aria-hidden="true" />
          Create governed request
        </CardTitle>
      </CardHeader>
      <CardContent>
        {policies.length ? (
          <form className="space-y-4" onSubmit={submit}>
            <div className="grid gap-3 md:grid-cols-2">
              <div>
                <Label htmlFor="request-policy">Published policy</Label>
                <Select value={policyId} onValueChange={setPolicyId}>
                  <SelectTrigger id="request-policy">
                    <SelectValue placeholder="Choose policy" />
                  </SelectTrigger>
                  <SelectContent>
                    {policies.map((value) => (
                      <SelectItem key={value.id} value={value.id}>
                        {value.name} · {value.actionType}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div>
                <Label htmlFor="request-object-id">
                  Reopen-requested month UUID
                </Label>
                <Input
                  id="request-object-id"
                  value={objectId}
                  onChange={(event) => setObjectId(event.target.value)}
                  required
                />
              </div>
            </div>
            <p className="text-xs text-muted-foreground">
              The server resolves the month engagement, current governance
              version, state and canonical SHA-256 evidence. These values
              cannot be supplied by the browser.
            </p>
            <p className="text-xs text-muted-foreground">
              Idempotency key {idempotencyKey}. Retries of this unchanged form
              reuse the same key.
            </p>
            <MutationNotice error={mutation.error} pending={mutation.isPending} />
            <Button
              type="submit"
              disabled={
                mutation.isPending ||
                !policyId ||
                !objectId.trim()
              }
            >
              Create approval request
            </Button>
          </form>
        ) : (
          <p className="rounded-md border border-dashed p-4 text-sm text-muted-foreground">
            No published policy is available. Publish and validate a policy
            version before creating a request.
          </p>
        )}
      </CardContent>
    </Card>
  );
}
