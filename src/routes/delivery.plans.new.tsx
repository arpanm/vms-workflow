import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { useState } from "react";

import { PageHeader } from "@/components/page-header";
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
import { Textarea } from "@/components/ui/textarea";
import type { CreatePlanRequest, DeliverableInput } from "@/features/delivery/contracts";
import { useCreatePlan } from "@/features/delivery/hooks";
import { DeliveryMutationError } from "@/features/delivery/query-boundary";
import { type FieldErrors, validateCreatePlanRequest } from "@/features/delivery/presentation";
import { MonthScope } from "@/features/workforce/scope-selectors";
import { requireDeliveryRoute } from "@/lib/delivery-route";

export const Route = createFileRoute("/delivery/plans/new")({
  beforeLoad: requireDeliveryRoute,
  head: () => ({ meta: [{ title: "Create delivery plan — Cadence" }] }),
  component: NewPlanPage,
});

const emptyDeliverable: DeliverableInput = {
  deliverableCode: "",
  title: "",
  description: "",
  businessObjective: "",
  projectId: "",
  productOwnerSubject: "",
  vendorOwnerSubject: "",
  priority: "P1",
  targetCompletionDate: "",
  evidenceExpectations: "",
  dependencyNoneDeclared: false,
  riskAndAssumptions: "",
  deliveryCategory: "FEATURE",
  criteria: [
    {
      statement: "",
      validationMethod: "",
      expectedResult: "",
      mandatory: true,
    },
  ],
  dependencies: [
    {
      type: "EXTERNAL",
      description: "",
      ownerSubject: "",
      targetResolutionDate: "",
      blocking: true,
    },
  ],
  assignments: [
    {
      employeeId: "",
      effectiveFrom: "",
    },
  ],
};

const list = (value: string) =>
  value
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);

function NewPlanPage() {
  return (
    <div>
      <PageHeader
        title="Create monthly plan"
        description="Create a provider-neutral draft. Linear links are attached after the server creates stable deliverable-version IDs."
      />
      <div className="p-6">
        <MonthScope>
          {(engagementMonthId) => <PlanBuilder engagementMonthId={engagementMonthId} />}
        </MonthScope>
      </div>
    </div>
  );
}

function PlanBuilder({ engagementMonthId }: { engagementMonthId: string }) {
  const navigate = useNavigate();
  const mutation = useCreatePlan();
  const [errors, setErrors] = useState<FieldErrors>({});
  const [plan, setPlan] = useState({
    title: "",
    summary: "",
    businessOutcomes: "",
    coordinatorSubject: "",
    baselineType: "ON_TIME" as CreatePlanRequest["baselineType"],
    quorumMode: "ANY_ONE" as CreatePlanRequest["quorumMode"],
    quorumRequired: 1,
    approvers: "",
    arrowFoundry: "",
    relianceStakeholders: "",
    procurementCc: "",
  });
  const [deliverable, setDeliverable] = useState<DeliverableInput>(emptyDeliverable);
  const [evidenceText, setEvidenceText] = useState("");

  const request = (): CreatePlanRequest => ({
    engagementMonthId,
    title: plan.title,
    summary: plan.summary,
    businessOutcomes: plan.businessOutcomes,
    coordinatorSubject: plan.coordinatorSubject,
    baselineType: plan.baselineType,
    quorumMode: plan.quorumMode,
    quorumRequired: plan.quorumRequired,
    approverSubjects: list(plan.approvers),
    recipients: {
      arrowFoundry: list(plan.arrowFoundry),
      relianceStakeholders: list(plan.relianceStakeholders),
      procurementCc: list(plan.procurementCc),
    },
    deliverables: [{ ...deliverable, evidenceExpectations: evidenceText.trim() }],
  });

  function submit(event: React.FormEvent) {
    event.preventDefault();
    const value = request();
    const nextErrors = validateCreatePlanRequest(value);
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length) return;
    mutation.mutate(value, {
      onSuccess: (created) =>
        navigate({
          to: "/delivery/plans/$planId",
          params: { planId: created.id },
        }),
    });
  }

  return (
    <form className="space-y-6" onSubmit={submit}>
      {Object.keys(errors).length > 0 && (
        <Card className="border-destructive/40" role="alert">
          <CardContent className="p-4">
            <p className="font-medium">Complete the required plan fields</p>
            <ul
              className="mt-2 list-disc space-y-1 pl-5 text-sm text-destructive"
              aria-label="Plan validation errors"
            >
              {Object.entries(errors).map(([field, error]) => (
                <li key={field}>{error}</li>
              ))}
            </ul>
          </CardContent>
        </Card>
      )}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Plan and approval</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-2">
          <TextField
            label="Plan title"
            value={plan.title}
            onChange={(title) => setPlan((v) => ({ ...v, title }))}
            error={errors.title}
          />
          <TextField
            label="Coordinator subject"
            value={plan.coordinatorSubject}
            onChange={(coordinatorSubject) => setPlan((v) => ({ ...v, coordinatorSubject }))}
            error={errors.coordinatorSubject}
          />
          <AreaField
            label="Summary"
            value={plan.summary}
            onChange={(summary) => setPlan((v) => ({ ...v, summary }))}
            error={errors.summary}
          />
          <AreaField
            label="Business outcomes"
            value={plan.businessOutcomes}
            onChange={(businessOutcomes) => setPlan((v) => ({ ...v, businessOutcomes }))}
            error={errors.businessOutcomes}
          />
          <TextField
            label="Approver subjects (comma-separated)"
            value={plan.approvers}
            onChange={(approvers) => setPlan((v) => ({ ...v, approvers }))}
            error={errors.approverSubjects}
          />
          <Select
            value={plan.baselineType}
            onValueChange={(baselineType: CreatePlanRequest["baselineType"]) =>
              setPlan((v) => ({ ...v, baselineType }))
            }
          >
            <SelectTrigger aria-label="Baseline type">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ON_TIME">On time</SelectItem>
              <SelectItem value="LATE_APPROVED">Late approved</SelectItem>
              <SelectItem value="HISTORICAL_RECONSTRUCTED">Historical reconstructed</SelectItem>
            </SelectContent>
          </Select>
          <div className="grid grid-cols-2 gap-3">
            <Select
              value={plan.quorumMode}
              onValueChange={(quorumMode: CreatePlanRequest["quorumMode"]) =>
                setPlan((v) => ({ ...v, quorumMode }))
              }
            >
              <SelectTrigger aria-label="Approval quorum mode">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ANY_ONE">Any one</SelectItem>
                <SelectItem value="ALL">All</SelectItem>
                <SelectItem value="N_OF_M">N of M</SelectItem>
              </SelectContent>
            </Select>
            <Input
              aria-label="Required approvals"
              type="number"
              min="1"
              value={plan.quorumRequired}
              onChange={(event) =>
                setPlan((v) => ({
                  ...v,
                  quorumRequired: Number(event.target.value),
                }))
              }
            />
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Commitment recipient preview</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-3">
          <TextField
            label="ArrowFoundry recipients"
            value={plan.arrowFoundry}
            onChange={(arrowFoundry) => setPlan((v) => ({ ...v, arrowFoundry }))}
          />
          <TextField
            label="Reliance product stakeholders"
            value={plan.relianceStakeholders}
            onChange={(relianceStakeholders) => setPlan((v) => ({ ...v, relianceStakeholders }))}
          />
          <TextField
            label="Central Procurement CC"
            value={plan.procurementCc}
            onChange={(procurementCc) => setPlan((v) => ({ ...v, procurementCc }))}
          />
          <p className="text-xs text-muted-foreground md:col-span-3">
            Enter server-recognized subjects or addresses separated by commas. The backend validates
            active contact groups and snapshots the exact preview. No email is sent by this form.
          </p>
        </CardContent>
      </Card>

      <DeliverableEditor
        value={deliverable}
        evidenceText={evidenceText}
        errors={errors}
        onChange={setDeliverable}
        onEvidenceChange={setEvidenceText}
      />
      <DeliveryMutationError error={mutation.error} />
      <div className="flex justify-end">
        <Button type="submit" disabled={mutation.isPending}>
          {mutation.isPending ? "Creating…" : "Create draft"}
        </Button>
      </div>
    </form>
  );
}

function DeliverableEditor({
  value,
  evidenceText,
  errors,
  onChange,
  onEvidenceChange,
}: {
  value: DeliverableInput;
  evidenceText: string;
  errors: FieldErrors;
  onChange: (value: DeliverableInput) => void;
  onEvidenceChange: (value: string) => void;
}) {
  const set = <K extends keyof DeliverableInput>(field: K, next: DeliverableInput[K]) =>
    onChange({ ...value, [field]: next });
  const error = (field: string) => errors[`deliverables.0.${field}`];

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Deliverable</CardTitle>
      </CardHeader>
      <CardContent className="grid gap-4 md:grid-cols-2">
        <TextField
          label="Deliverable code"
          value={value.deliverableCode}
          onChange={(v) => set("deliverableCode", v)}
          error={error("deliverableCode")}
        />
        <TextField
          label="Title"
          value={value.title}
          onChange={(v) => set("title", v)}
          error={error("title")}
        />
        <AreaField
          label="Description"
          value={value.description}
          onChange={(v) => set("description", v)}
          error={error("description")}
        />
        <AreaField
          label="Business objective"
          value={value.businessObjective}
          onChange={(v) => set("businessObjective", v)}
          error={error("businessObjective")}
        />
        <TextField
          label="Project ID"
          value={value.projectId}
          onChange={(v) => set("projectId", v)}
          error={error("projectId")}
        />
        <TextField
          label="Reliance product-owner subject"
          value={value.productOwnerSubject}
          onChange={(v) => set("productOwnerSubject", v)}
          error={error("productOwnerSubject")}
        />
        <TextField
          label="ArrowFoundry owner subject"
          value={value.vendorOwnerSubject}
          onChange={(v) => set("vendorOwnerSubject", v)}
          error={error("vendorOwnerSubject")}
        />
        <TextField
          label="Target completion date"
          type="date"
          value={value.targetCompletionDate}
          onChange={(v) => set("targetCompletionDate", v)}
          error={error("targetCompletionDate")}
        />
        <Select
          value={value.priority}
          onValueChange={(next: DeliverableInput["priority"]) => set("priority", next)}
        >
          <SelectTrigger aria-label="Priority">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="P0">P0</SelectItem>
            <SelectItem value="P1">P1</SelectItem>
            <SelectItem value="P2">P2</SelectItem>
            <SelectItem value="P3">P3</SelectItem>
          </SelectContent>
        </Select>
        <Select
          value={value.deliveryCategory}
          onValueChange={(next: DeliverableInput["deliveryCategory"]) =>
            set("deliveryCategory", next)
          }
        >
          <SelectTrigger aria-label="Delivery category">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {[
              "FEATURE",
              "PLATFORM",
              "INTEGRATION",
              "QUALITY",
              "OPERATIONS",
              "RESEARCH_POC",
              "SUPPORT",
              "OTHER",
            ].map((category) => (
              <SelectItem value={category} key={category}>
                {category.replace("_", " ")}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <TextField
          label="Evidence expectations (comma-separated)"
          value={evidenceText}
          onChange={onEvidenceChange}
          error={error("evidenceExpectations")}
        />
        <TextField
          label="Assigned employee ID"
          value={value.assignments[0]?.employeeId ?? ""}
          onChange={(employeeId) =>
            set("assignments", [
              {
                ...(value.assignments[0] ?? {}),
                employeeId,
                effectiveFrom: value.assignments[0]?.effectiveFrom ?? "",
              },
            ])
          }
          error={error("assignments")}
        />
        <TextField
          label="Assignment effective from"
          type="date"
          value={value.assignments[0]?.effectiveFrom ?? ""}
          onChange={(effectiveFrom) =>
            set("assignments", [
              {
                ...(value.assignments[0] ?? { employeeId: "" }),
                effectiveFrom,
              },
            ])
          }
        />
        <AreaField
          label="Risks and assumptions (enter None when applicable)"
          value={value.riskAndAssumptions}
          onChange={(v) => set("riskAndAssumptions", v)}
          error={error("riskAndAssumptions")}
        />
        <AreaField
          label="Acceptance criterion"
          value={value.criteria[0]?.statement ?? ""}
          onChange={(statement) => set("criteria", [{ ...value.criteria[0]!, statement }])}
          error={error("criteria")}
        />
        <TextField
          label="Criterion validation method"
          value={value.criteria[0]?.validationMethod ?? ""}
          onChange={(validationMethod) =>
            set("criteria", [{ ...value.criteria[0]!, validationMethod }])
          }
        />
        <TextField
          label="Criterion expected result"
          value={value.criteria[0]?.expectedResult ?? ""}
          onChange={(expectedResult) =>
            set("criteria", [{ ...value.criteria[0]!, expectedResult }])
          }
        />
        <div className="flex items-center gap-2">
          <Checkbox
            id="dependency-none"
            checked={value.dependencyNoneDeclared}
            onCheckedChange={(checked) =>
              onChange({
                ...value,
                dependencyNoneDeclared: checked === true,
                dependencies:
                  checked === true
                    ? []
                    : value.dependencies.length
                      ? value.dependencies
                      : [
                          {
                            type: "EXTERNAL",
                            description: "",
                            ownerSubject: "",
                            targetResolutionDate: "",
                            blocking: true,
                          },
                        ],
              })
            }
          />
          <Label htmlFor="dependency-none">Explicitly declare no dependencies</Label>
        </div>
        {error("dependencies") && (
          <p className="text-xs text-destructive" role="alert">
            {error("dependencies")}
          </p>
        )}
        {!value.dependencyNoneDeclared && value.dependencies[0] && (
          <div className="grid gap-3 rounded-md border p-3 md:col-span-2 md:grid-cols-2">
            <Select
              value={value.dependencies[0].type}
              onValueChange={(type: DeliverableInput["dependencies"][number]["type"]) =>
                set("dependencies", [{ ...value.dependencies[0]!, type }])
              }
            >
              <SelectTrigger aria-label="Dependency type">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="INTERNAL">Internal deliverable</SelectItem>
                <SelectItem value="LINEAR">Linear issue</SelectItem>
                <SelectItem value="EXTERNAL">External</SelectItem>
              </SelectContent>
            </Select>
            <TextField
              label="Dependency description"
              value={value.dependencies[0].description}
              onChange={(description) =>
                set("dependencies", [{ ...value.dependencies[0]!, description }])
              }
            />
            {value.dependencies[0].type === "INTERNAL" && (
              <TextField
                label="Depends-on deliverable ID"
                value={value.dependencies[0].dependsOnDeliverableId ?? ""}
                onChange={(dependsOnDeliverableId) =>
                  set("dependencies", [
                    {
                      ...value.dependencies[0]!,
                      dependsOnDeliverableId,
                    },
                  ])
                }
              />
            )}
            <TextField
              label="Dependency owner subject"
              value={value.dependencies[0].ownerSubject}
              onChange={(ownerSubject) =>
                set("dependencies", [{ ...value.dependencies[0]!, ownerSubject }])
              }
            />
            <TextField
              label="Target resolution date"
              type="date"
              value={value.dependencies[0].targetResolutionDate}
              onChange={(targetResolutionDate) =>
                set("dependencies", [{ ...value.dependencies[0]!, targetResolutionDate }])
              }
            />
            <div className="flex items-center gap-2">
              <Checkbox
                id="dependency-blocking"
                checked={value.dependencies[0].blocking}
                onCheckedChange={(checked) =>
                  set("dependencies", [
                    {
                      ...value.dependencies[0]!,
                      blocking: checked === true,
                    },
                  ])
                }
              />
              <Label htmlFor="dependency-blocking">Blocking dependency</Label>
            </div>
          </div>
        )}
        <TextField
          label="Authorized Linear-link exception reason (optional)"
          value={value.linkExceptionReason ?? ""}
          onChange={(next) => set("linkExceptionReason", next)}
        />
        <p className="text-xs text-muted-foreground md:col-span-2">
          Linear linking occurs from the created plan detail after a stable deliverable-version ID
          exists. A link exception remains subject to server authorization.
        </p>
      </CardContent>
    </Card>
  );
}

function TextField({
  label,
  value,
  onChange,
  error,
  type = "text",
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  error?: string;
  type?: string;
}) {
  return (
    <div className="space-y-1.5">
      <Label>{label}</Label>
      <Input
        type={type}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        aria-invalid={Boolean(error)}
      />
      {error && (
        <p className="text-xs text-destructive" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

function AreaField({
  label,
  value,
  onChange,
  error,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  error?: string;
}) {
  return (
    <div className="space-y-1.5">
      <Label>{label}</Label>
      <Textarea
        value={value}
        onChange={(event) => onChange(event.target.value)}
        aria-invalid={Boolean(error)}
      />
      {error && (
        <p className="text-xs text-destructive" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
