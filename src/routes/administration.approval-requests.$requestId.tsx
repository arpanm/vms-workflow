import { createFileRoute } from "@tanstack/react-router";
import { ArrowRight, CheckCircle2, GitBranch, ShieldCheck } from "lucide-react";
import { useState } from "react";

import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import {
  CoreAdminBoundary,
  MutationNotice,
} from "@/features/core-admin/components";
import type {
  ApprovalActionInput,
  ApprovalRequestView,
  DelegationView,
} from "@/features/core-admin/contracts";
import {
  useApprovalRequest,
  useApprovalRequestAction,
  useDelegations,
} from "@/features/core-admin/hooks";
import { coreAdminPermissions } from "@/features/core-admin/permissions";
import { useActiveScope } from "@/features/core-admin/scope-provider";

export const Route = createFileRoute(
  "/administration/approval-requests/$requestId",
)({
  head: () => ({
    meta: [
      { title: "Approval request — Cadence" },
      {
        name: "description",
        content:
          "Exact object-version approval request, stage quorum and attributable action history.",
      },
    ],
  }),
  component: ApprovalRequestPage,
});

function ApprovalRequestPage() {
  const { requestId } = Route.useParams();
  const scope = useActiveScope();
  const readable = scope.can(coreAdminPermissions.approvalRequestRead);
  const query = useApprovalRequest(requestId, readable);
  const canAct = scope.can(coreAdminPermissions.approvalRequestAct);
  const delegations = useDelegations(
    query.data?.engagementId ?? "",
    readable && canAct && query.data?.status === "PENDING",
  );

  return (
    <div>
      <PageHeader
        title="Approval request"
        description="The decision is bound to the displayed policy, object version and hash. Quorum is evaluated only by the server."
      />
      <CoreAdminBoundary authorized={readable} query={query}>
        {query.data ? (
          <ApprovalRequestDetail
            key={`${query.data.id}:${query.data.version}`}
            request={query.data}
            delegations={delegations.data ?? []}
            canAct={canAct}
            onReload={() => void query.refetch()}
          />
        ) : null}
      </CoreAdminBoundary>
    </div>
  );
}

function ApprovalRequestDetail({
  request,
  delegations,
  canAct,
  onReload,
}: {
  request: ApprovalRequestView;
  delegations: DelegationView[];
  canAct: boolean;
  onReload: () => void;
}) {
  const mutation = useApprovalRequestAction(request.engagementId, request.id);
  const [decision, setDecision] =
    useState<ApprovalActionInput["decision"]>("APPROVED");
  const [reason, setReason] = useState("");
  const [delegationId, setDelegationId] = useState("DIRECT");
  const [idempotencyKey] = useState(() => crypto.randomUUID());
  const activeDelegations = delegations.filter(
    (delegation) =>
      delegation.status === "ACTIVE" &&
      delegation.actionCodes.includes(request.requiredPermissionCode),
  );
  const reasonRequired =
    request.evidenceRequired || decision !== "APPROVED";

  function act(event: React.FormEvent) {
    event.preventDefault();
    mutation.mutate({
      decision,
      reason: reason.trim() || null,
      delegationId: delegationId === "DIRECT" ? null : delegationId,
      idempotencyKey,
      expectedRequestVersion: request.version,
    });
  }

  return (
    <div className="grid gap-5 p-6 xl:grid-cols-[1fr_1.4fr]">
      <div className="space-y-5">
        <Card>
          <CardHeader>
            <div className="flex items-start justify-between gap-3">
              <CardTitle className="text-base">
                {request.objectType} · version {request.objectVersion}
              </CardTitle>
              <span aria-label={`Request status ${request.status}`}>
                <StatusBadge status={request.status} />
              </span>
            </div>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            <Field label="Object ID" value={request.objectId} mono />
            <Field label="Object SHA-256" value={request.objectHash} mono />
            <Field label="Policy version ID" value={request.policyVersionId} mono />
            <Field
              label="Required business permission"
              value={request.requiredPermissionCode}
            />
            <Field
              label="Requested by"
              value={`${request.requestedBySubject} · ${new Date(request.requestedAt).toLocaleString()}`}
            />
            <Field label="Request version" value={String(request.version)} />
          </CardContent>
        </Card>
        {request.status === "PENDING" && canAct ? (
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <ShieldCheck className="h-4 w-4" aria-hidden="true" />
                Record eligible action
              </CardTitle>
            </CardHeader>
            <CardContent>
              <form className="space-y-4" onSubmit={act}>
                <div>
                  <Label htmlFor="approval-decision">Decision</Label>
                  <Select
                    value={decision}
                    onValueChange={(value) =>
                      setDecision(value as ApprovalActionInput["decision"])
                    }
                  >
                    <SelectTrigger id="approval-decision">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {[
                        "APPROVED",
                        "REJECTED",
                        "CHANGES_REQUESTED",
                        "CANCELLED",
                      ].map((value) => (
                        <SelectItem key={value} value={value}>
                          {value.replaceAll("_", " ")}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <div>
                  <Label htmlFor="approval-delegation">Authority source</Label>
                  <Select value={delegationId} onValueChange={setDelegationId}>
                    <SelectTrigger id="approval-delegation">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="DIRECT">Direct assignment</SelectItem>
                      {activeDelegations.map((delegation) => (
                        <SelectItem key={delegation.id} value={delegation.id}>
                          Delegated from{" "}
                          {delegation.delegatorName ?? delegation.delegatorUserId}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <div>
                  <Label htmlFor="approval-reason">
                    Reason{reasonRequired ? " (required)" : ""}
                  </Label>
                  <Textarea
                    id="approval-reason"
                    value={reason}
                    onChange={(event) => setReason(event.target.value)}
                    rows={4}
                    required={reasonRequired}
                    placeholder="Record evidence, observations or required changes."
                  />
                </div>
                <MutationNotice
                  error={mutation.error}
                  pending={mutation.isPending}
                  onReload={onReload}
                />
                <Button
                  type="submit"
                  disabled={
                    mutation.isPending || (reasonRequired && !reason.trim())
                  }
                >
                  Submit exact-version decision
                </Button>
              </form>
            </CardContent>
          </Card>
        ) : request.status === "PENDING" ? (
          <p className="rounded-md border border-dashed p-4 text-sm text-muted-foreground">
            Read-only: approval.request.act is not granted in this scope.
          </p>
        ) : null}
      </div>
      <div className="space-y-5">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <GitBranch className="h-4 w-4" aria-hidden="true" />
              Policy stages and quorum
            </CardTitle>
          </CardHeader>
          <CardContent>
            <ol className="space-y-3">
              {request.stages.map((stage) => {
                const actions = request.actions.filter(
                  (action) => action.stageOrder === stage.stageOrder,
                );
                const current = request.currentStageOrder === stage.stageOrder;
                return (
                  <li
                    key={stage.id}
                    className={`rounded-md border p-4 ${current ? "border-primary/50 bg-primary/5" : ""}`}
                  >
                    <div className="flex items-start justify-between gap-3">
                      <div>
                        <p className="font-medium">
                          {stage.stageOrder}. {stage.name}
                        </p>
                        <p className="text-xs text-muted-foreground">
                          {stage.roleCode ?? "Scoped assignee"} ·{" "}
                          {stage.quorumMode} ({stage.quorumRequired})
                        </p>
                      </div>
                      {actions.length >= stage.quorumRequired ? (
                        <CheckCircle2
                          className="h-5 w-5 text-emerald-600"
                          aria-label="Recorded quorum reached"
                        />
                      ) : current ? (
                        <span className="text-xs font-medium text-primary">
                          Current stage
                        </span>
                      ) : null}
                    </div>
                    {actions.length ? (
                      <ul className="mt-3 space-y-2 border-t pt-3">
                        {actions.map((action) => (
                          <li key={action.id} className="text-sm">
                            <p className="flex items-center gap-2">
                              <StatusBadge status={action.decision} />
                              <ArrowRight className="h-3 w-3" aria-hidden="true" />
                              <span>{action.actorSubject}</span>
                            </p>
                            <p className="mt-1 text-xs text-muted-foreground">
                              {action.reason ?? "No comment"} · {action.source}
                              {action.delegationId
                                ? ` · delegation ${action.delegationId}`
                                : ""}
                            </p>
                          </li>
                        ))}
                      </ul>
                    ) : null}
                  </li>
                );
              })}
            </ol>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

function Field({
  label,
  value,
  mono = false,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div>
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className={`break-all font-medium ${mono ? "font-mono text-xs" : ""}`}>
        {value}
      </p>
    </div>
  );
}
