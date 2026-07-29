import { createFileRoute } from "@tanstack/react-router";
import {
  GitPullRequestArrow,
  Plus,
  Send,
  ShieldCheck,
  UserRoundCog,
} from "lucide-react";
import { useState } from "react";

import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  CoreAdminBoundary,
  MutationNotice,
} from "@/features/core-admin/components";
import type {
  ApprovalPolicyView,
  ApprovalStageInput,
  DelegationView,
  EligibleUserView,
} from "@/features/core-admin/contracts";
import {
  useApprovalPolicies,
  useCreateApprovalPolicy,
  useCreateDelegation,
  useDelegations,
  useEligibleUsers,
  usePublishApprovalPolicy,
  useReviseApprovalPolicy,
  useRevokeDelegation,
} from "@/features/core-admin/hooks";
import { coreAdminPermissions } from "@/features/core-admin/permissions";
import { useActiveScope } from "@/features/core-admin/scope-provider";

export const Route = createFileRoute("/administration/approval-policies")({
  head: () => ({
    meta: [
      { title: "Approval policies & delegations — Cadence" },
      {
        name: "description",
        content:
          "Versioned approval routing and effective-dated delegation administration.",
      },
    ],
  }),
  component: ApprovalPoliciesPage,
});

const ACTION_TYPES = [
  "PLAN_APPROVAL",
  "LEAVE_APPROVAL",
  "REGULARIZATION",
  "ATTENDANCE_CORRECTION",
  "DELIVERY_CERTIFICATION",
  "MONTH_CONFIRMATION",
  "REOPEN",
  "PROCUREMENT_EXCEPTION",
] as const;

const blankStage = (): ApprovalStageInput => ({
  name: "Initial review",
  roleCode: "CLIENT_APPROVER",
  quorumMode: "ANY_ONE",
  quorumRequired: 1,
  allowDelegation: true,
  dueDurationHours: 24,
});

function ApprovalPoliciesPage() {
  const scope = useActiveScope();
  const engagementId = scope.selection.engagementId;
  const canPolicies = scope.can(coreAdminPermissions.approvalPolicyManage);
  const canDelegations = scope.can(coreAdminPermissions.delegationManage);
  const policies = useApprovalPolicies(engagementId, canPolicies);
  const delegations = useDelegations(engagementId, canDelegations);
  const eligibleUsers = useEligibleUsers(
    engagementId,
    scope.selection.organizationId,
    canDelegations,
  );

  return (
    <div>
      <PageHeader
        title="Approval policies & delegations"
        description="Publishing creates a new effective policy version. Delegations are bounded by eligibility, scope, action and time."
      />
      <Tabs defaultValue={canPolicies ? "policies" : "delegations"} className="p-6">
        <TabsList>
          {canPolicies ? (
            <TabsTrigger value="policies">Approval policies</TabsTrigger>
          ) : null}
          {canDelegations ? (
            <TabsTrigger value="delegations">Delegations</TabsTrigger>
          ) : null}
        </TabsList>
        <TabsContent value="policies" className="space-y-5">
          <CoreAdminBoundary authorized={canPolicies} query={policies}>
            <PolicyBuilder engagementId={engagementId} />
            <div className="mt-5 grid gap-4 xl:grid-cols-2">
              {policies.data?.map((policy) => (
                <PolicyCard key={policy.id} policy={policy} />
              ))}
            </div>
            {!policies.data?.length ? (
              <CoreAdminBoundary
                empty
                emptyTitle="No approval policies"
                emptyDescription="Build the first policy and validate its stage assignment and quorum before publishing."
              >
                {null}
              </CoreAdminBoundary>
            ) : null}
          </CoreAdminBoundary>
        </TabsContent>
        <TabsContent value="delegations" className="space-y-5">
          <CoreAdminBoundary authorized={canDelegations} query={delegations}>
            <CoreAdminBoundary query={eligibleUsers}>
              <DelegationBuilder
                engagementId={engagementId}
                organizationId={scope.selection.organizationId}
                users={eligibleUsers.data ?? []}
              />
            </CoreAdminBoundary>
            <div className="mt-5 grid gap-4 xl:grid-cols-2">
              {delegations.data?.map((delegation) => (
                <DelegationCard key={delegation.id} delegation={delegation} />
              ))}
            </div>
            {!delegations.data?.length ? (
              <CoreAdminBoundary
                empty
                emptyTitle="No delegations"
                emptyDescription="There are no current or historical delegations in this engagement."
              >
                {null}
              </CoreAdminBoundary>
            ) : null}
          </CoreAdminBoundary>
        </TabsContent>
      </Tabs>
    </div>
  );
}

function PolicyBuilder({ engagementId }: { engagementId: string }) {
  const mutation = useCreateApprovalPolicy(engagementId);
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [actionType, setActionType] =
    useState<(typeof ACTION_TYPES)[number]>("PLAN_APPROVAL");
  const [validFrom, setValidFrom] = useState(
    new Date().toISOString().slice(0, 10),
  );
  const [prohibitSelfApproval, setProhibitSelfApproval] = useState(true);
  const [evidenceRequired, setEvidenceRequired] = useState(true);
  const [stages, setStages] = useState<ApprovalStageInput[]>([blankStage()]);

  function updateStage(index: number, next: Partial<ApprovalStageInput>) {
    setStages((current) =>
      current.map((stage, offset) =>
        offset === index ? { ...stage, ...next } : stage,
      ),
    );
  }

  function submit(event: React.FormEvent) {
    event.preventDefault();
    mutation.mutate(
      {
        code: code.trim().toUpperCase(),
        name: name.trim(),
        actionType,
        validFrom,
        prohibitSelfApproval,
        evidenceRequired,
        rules: {},
        stages,
      },
      {
        onSuccess: () => {
          setCode("");
          setName("");
          setStages([blankStage()]);
        },
      },
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <Plus className="h-4 w-4" aria-hidden="true" />
          Policy builder
        </CardTitle>
      </CardHeader>
      <CardContent>
        <form className="space-y-4" onSubmit={submit}>
          <div className="grid gap-3 md:grid-cols-4">
            <div>
              <Label htmlFor="policy-code">Code</Label>
              <Input
                id="policy-code"
                value={code}
                onChange={(event) => setCode(event.target.value)}
                required
              />
            </div>
            <div>
              <Label htmlFor="policy-name">Name</Label>
              <Input
                id="policy-name"
                value={name}
                onChange={(event) => setName(event.target.value)}
                required
              />
            </div>
            <div>
              <Label htmlFor="policy-action">Action</Label>
              <Select
                value={actionType}
                onValueChange={(value) =>
                  setActionType(value as (typeof ACTION_TYPES)[number])
                }
              >
                <SelectTrigger id="policy-action">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {ACTION_TYPES.map((value) => (
                    <SelectItem key={value} value={value}>
                      {value.replaceAll("_", " ")}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div>
              <Label htmlFor="policy-valid-from">Effective from</Label>
              <Input
                id="policy-valid-from"
                type="date"
                value={validFrom}
                onChange={(event) => setValidFrom(event.target.value)}
                required
              />
            </div>
          </div>
          <div className="space-y-3 rounded-lg border p-4">
            <div className="flex items-center justify-between gap-3">
              <p className="font-medium">Ordered stages</p>
              <Button
                type="button"
                size="sm"
                variant="outline"
                onClick={() =>
                  setStages((current) => [
                    ...current,
                    { ...blankStage(), name: `Stage ${current.length + 1}` },
                  ])
                }
              >
                Add stage
              </Button>
            </div>
            {stages.map((stage, index) => (
              <div
                key={index}
                className="grid gap-3 border-t pt-3 md:grid-cols-[2fr_1.5fr_1fr_1fr_auto]"
              >
                <div>
                  <Label htmlFor={`stage-name-${index}`}>
                    Stage {index + 1} name
                  </Label>
                  <Input
                    id={`stage-name-${index}`}
                    value={stage.name}
                    onChange={(event) =>
                      updateStage(index, { name: event.target.value })
                    }
                    required
                  />
                </div>
                <div>
                  <Label htmlFor={`stage-role-${index}`}>Required role</Label>
                  <Input
                    id={`stage-role-${index}`}
                    value={stage.roleCode ?? ""}
                    onChange={(event) =>
                      updateStage(index, {
                        roleCode: event.target.value,
                        contactGroupId: null,
                        explicitAssigneeId: null,
                      })
                    }
                    required
                  />
                </div>
                <div>
                  <Label htmlFor={`stage-quorum-${index}`}>Quorum</Label>
                  <Select
                    value={stage.quorumMode}
                    onValueChange={(value) =>
                      updateStage(index, {
                        quorumMode: value as ApprovalStageInput["quorumMode"],
                        quorumRequired:
                          value === "ANY_ONE" ? 1 : stage.quorumRequired,
                      })
                    }
                  >
                    <SelectTrigger id={`stage-quorum-${index}`}>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {["ANY_ONE", "ALL", "N_OF_M"].map((value) => (
                        <SelectItem key={value} value={value}>
                          {value.replaceAll("_", " ")}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <div>
                  <Label htmlFor={`stage-required-${index}`}>Required</Label>
                  <Input
                    id={`stage-required-${index}`}
                    type="number"
                    min={1}
                    value={stage.quorumRequired}
                    disabled={stage.quorumMode === "ANY_ONE"}
                    onChange={(event) =>
                      updateStage(index, {
                        quorumRequired: Number(event.target.value),
                      })
                    }
                  />
                </div>
                <Button
                  className="self-end"
                  type="button"
                  variant="ghost"
                  disabled={stages.length === 1}
                  onClick={() =>
                    setStages((current) =>
                      current.filter((_, offset) => offset !== index),
                    )
                  }
                >
                  Remove
                </Button>
              </div>
            ))}
          </div>
          <div className="flex flex-wrap gap-6">
            <label className="flex items-center gap-2 text-sm">
              <Checkbox
                checked={prohibitSelfApproval}
                onCheckedChange={(value) => setProhibitSelfApproval(value === true)}
              />
              Prohibit self-approval
            </label>
            <label className="flex items-center gap-2 text-sm">
              <Checkbox
                checked={evidenceRequired}
                onCheckedChange={(value) => setEvidenceRequired(value === true)}
              />
              Evidence required
            </label>
          </div>
          <MutationNotice error={mutation.error} pending={mutation.isPending} />
          <Button
            type="submit"
            disabled={
              mutation.isPending ||
              !code.trim() ||
              !name.trim() ||
              stages.some((stage) => !stage.name.trim() || !stage.roleCode?.trim())
            }
          >
            Create draft policy
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}

function PolicyCard({ policy }: { policy: ApprovalPolicyView }) {
  const mutation = usePublishApprovalPolicy(policy.engagementId, policy.id);
  const revision = useReviseApprovalPolicy(policy.engagementId, policy.id);
  const [revisionValidFrom, setRevisionValidFrom] = useState("");
  return (
    <Card>
      <CardHeader>
        <div className="flex items-start justify-between gap-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <GitPullRequestArrow className="h-4 w-4" aria-hidden="true" />
            {policy.name}
          </CardTitle>
          <StatusBadge status={policy.versionStatus} />
        </div>
        <p className="text-xs text-muted-foreground">
          {policy.code} · {policy.actionType.replaceAll("_", " ")} · policy
          version {policy.policyVersion}
        </p>
      </CardHeader>
      <CardContent className="space-y-3">
        <ol className="space-y-2">
          {policy.stages.map((stage) => (
            <li key={stage.id} className="rounded-md border p-3 text-sm">
              <p className="font-medium">
                {stage.stageOrder}. {stage.name}
              </p>
              <p className="text-xs text-muted-foreground">
                {stage.roleCode ?? "Scoped assignee"} · {stage.quorumMode} (
                {stage.quorumRequired}) · delegation{" "}
                {stage.allowDelegation ? "allowed" : "blocked"}
              </p>
            </li>
          ))}
        </ol>
        <p className="text-xs text-muted-foreground">
          Effective {policy.validFrom}
          {policy.validTo ? ` through ${policy.validTo}` : ""}. Self-approval{" "}
          {policy.prohibitSelfApproval ? "prohibited" : "permitted by policy"}.
        </p>
        <MutationNotice error={mutation.error} pending={mutation.isPending} />
        {policy.versionStatus === "DRAFT" ? (
          <Button
            size="sm"
            onClick={() =>
              mutation.mutate({ expectedPolicyVersion: policy.version })
            }
            disabled={mutation.isPending}
          >
            <Send className="mr-2 h-4 w-4" aria-hidden="true" />
            Publish immutable version
          </Button>
        ) : null}
        {policy.versionStatus === "PUBLISHED" ? (
          <div className="space-y-2 rounded-md border p-3">
            <Label htmlFor={`revision-effective-${policy.id}`}>
              Revision effective from
            </Label>
            <div className="flex flex-wrap gap-2">
              <Input
                id={`revision-effective-${policy.id}`}
                className="max-w-48"
                type="date"
                min={policy.validFrom}
                value={revisionValidFrom}
                onChange={(event) =>
                  setRevisionValidFrom(event.target.value)
                }
              />
              <Button
                type="button"
                size="sm"
                variant="outline"
                disabled={revision.isPending || !revisionValidFrom}
                onClick={() =>
                  revision.mutate({
                    name: policy.name,
                    validFrom: revisionValidFrom,
                    validTo: null,
                    prohibitSelfApproval: policy.prohibitSelfApproval,
                    evidenceRequired: policy.evidenceRequired,
                    rules: policy.rules,
                    stages: policy.stages.map((stage) => ({
                      name: stage.name,
                      roleCode: stage.roleCode,
                      contactGroupId: stage.contactGroupId,
                      explicitAssigneeId: stage.explicitAssigneeId,
                      quorumMode: stage.quorumMode,
                      quorumRequired: stage.quorumRequired,
                      allowDelegation: stage.allowDelegation,
                      dueDurationHours: stage.dueDurationHours,
                    })),
                    expectedPolicyVersion: policy.version,
                  })
                }
              >
                Create draft revision
              </Button>
            </div>
            <p className="text-xs text-muted-foreground">
              The new draft keeps this policy identity. Publishing it
              supersedes, but never rewrites, the current version.
            </p>
            <MutationNotice
              error={revision.error}
              pending={revision.isPending}
            />
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}

function DelegationBuilder({
  engagementId,
  organizationId,
  users,
}: {
  engagementId: string;
  organizationId: string;
  users: EligibleUserView[];
}) {
  const mutation = useCreateDelegation(engagementId);
  const [delegatorUserId, setDelegatorUserId] = useState("");
  const [delegateUserId, setDelegateUserId] = useState("");
  const [actions, setActions] = useState("PLAN_APPROVAL");
  const [validFrom, setValidFrom] = useState("");
  const [validTo, setValidTo] = useState("");
  const [reason, setReason] = useState("");

  function submit(event: React.FormEvent) {
    event.preventDefault();
    mutation.mutate({
      organizationId,
      delegatorUserId,
      delegateUserId,
      actionCodes: actions
        .split(",")
        .map((value) => value.trim())
        .filter(Boolean),
      validFrom: new Date(validFrom).toISOString(),
      validTo: new Date(validTo).toISOString(),
      reason: reason.trim(),
    });
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <UserRoundCog className="h-4 w-4" aria-hidden="true" />
          Create time-bounded delegation
        </CardTitle>
      </CardHeader>
      <CardContent>
        {users.length < 2 ? (
          <p className="rounded-md border border-dashed p-4 text-sm text-muted-foreground">
            At least two eligible users in this organization are required.
          </p>
        ) : (
          <form className="space-y-4" onSubmit={submit}>
            <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
              <UserSelect
                id="delegator"
                label="Authority holder"
                value={delegatorUserId}
                users={users}
                onChange={setDelegatorUserId}
              />
              <UserSelect
                id="delegate"
                label="Delegate"
                value={delegateUserId}
                users={users.filter((user) => user.id !== delegatorUserId)}
                onChange={setDelegateUserId}
              />
              <div>
                <Label htmlFor="delegation-from">Valid from</Label>
                <Input
                  id="delegation-from"
                  type="datetime-local"
                  value={validFrom}
                  onChange={(event) => setValidFrom(event.target.value)}
                  required
                />
              </div>
              <div>
                <Label htmlFor="delegation-to">Valid to</Label>
                <Input
                  id="delegation-to"
                  type="datetime-local"
                  value={validTo}
                  onChange={(event) => setValidTo(event.target.value)}
                  required
                />
              </div>
              <div className="md:col-span-2">
                <Label htmlFor="delegation-actions">
                  Action codes (comma separated)
                </Label>
                <Input
                  id="delegation-actions"
                  value={actions}
                  onChange={(event) => setActions(event.target.value)}
                  required
                />
              </div>
              <div className="md:col-span-2">
                <Label htmlFor="delegation-reason">Reason</Label>
                <Input
                  id="delegation-reason"
                  value={reason}
                  onChange={(event) => setReason(event.target.value)}
                  required
                />
              </div>
            </div>
            <MutationNotice error={mutation.error} pending={mutation.isPending} />
            <Button
              type="submit"
              disabled={
                mutation.isPending ||
                !delegatorUserId ||
                !delegateUserId ||
                !validFrom ||
                !validTo ||
                !reason.trim()
              }
            >
              Create delegation
            </Button>
          </form>
        )}
      </CardContent>
    </Card>
  );
}

function UserSelect({
  id,
  label,
  value,
  users,
  onChange,
}: {
  id: string;
  label: string;
  value: string;
  users: EligibleUserView[];
  onChange: (value: string) => void;
}) {
  return (
    <div>
      <Label htmlFor={id}>{label}</Label>
      <Select value={value} onValueChange={onChange}>
        <SelectTrigger id={id}>
          <SelectValue placeholder="Select eligible user" />
        </SelectTrigger>
        <SelectContent>
          {users.map((user) => (
            <SelectItem key={user.id} value={user.id}>
              {user.displayName} · {user.activeRoleCodes.join(", ")}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}

function DelegationCard({ delegation }: { delegation: DelegationView }) {
  const mutation = useRevokeDelegation(delegation.engagementId ?? "", delegation.id);
  const [reason, setReason] = useState("");
  return (
    <Card>
      <CardHeader>
        <div className="flex items-start justify-between gap-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <ShieldCheck className="h-4 w-4" aria-hidden="true" />
            {delegation.delegatorName ?? delegation.delegatorUserId} →{" "}
            {delegation.delegateName ?? delegation.delegateUserId}
          </CardTitle>
          <StatusBadge status={delegation.status} />
        </div>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        <p>{delegation.actionCodes.join(", ")}</p>
        <p className="text-muted-foreground">
          {new Date(delegation.validFrom).toLocaleString()} —{" "}
          {new Date(delegation.validTo).toLocaleString()}
        </p>
        <p>{delegation.reason}</p>
        <MutationNotice error={mutation.error} pending={mutation.isPending} />
        {delegation.status === "ACTIVE" ? (
          <div className="flex gap-2">
            <Input
              aria-label="Revocation reason"
              placeholder="Reason required to revoke"
              value={reason}
              onChange={(event) => setReason(event.target.value)}
            />
            <Button
              variant="destructive"
              disabled={mutation.isPending || !reason.trim()}
              onClick={() =>
                mutation.mutate({
                  reason: reason.trim(),
                  expectedVersion: delegation.version,
                })
              }
            >
              Revoke
            </Button>
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}
