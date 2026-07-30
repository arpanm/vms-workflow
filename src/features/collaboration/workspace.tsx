import { useMemo, useState, type FormEvent, type ReactNode } from "react";
import { ExternalLink, MessageSquareText, Plus, Trash2, Users } from "lucide-react";
import { toast } from "sonner";

import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { useActiveScope } from "@/features/core-admin/scope-provider";
import { useSession } from "@/features/auth/session-provider";

import { collaborationApi } from "./api";
import type {
  CreateWorkItemInput,
  Discipline,
  LinkType,
  WorkItem,
  WorkItemBucket,
  WorkItemStatus,
} from "./contracts";
import { useCollaborationMutation, useCreateWorkItem, useWorkItems } from "./hooks";

const statuses: WorkItemStatus[] = [
  "BACKLOG",
  "PLANNED",
  "APPROVED",
  "IN_PROGRESS",
  "BLOCKED",
  "DELIVERED",
  "PARTIALLY_DELIVERED",
  "NOT_DELIVERED",
  "CANCELLED",
];
const buckets: WorkItemBucket[] = ["ALL", "BACKLOG", "CURRENT", "NEXT", "PAST"];
const disciplines: Discipline[] = [
  "DEVELOPER",
  "QA",
  "PRODUCT_MANAGER",
  "PROGRAM_MANAGER",
  "UX_DESIGNER",
  "DEVOPS",
  "DATA_ANALYST",
  "OTHER",
];
const linkTypes: LinkType[] = [
  "DOCUMENT",
  "PRD",
  "USER_STORY",
  "FIGMA",
  "PROTOTYPE",
  "LINEAR",
  "JIRA",
  "CODE_REVIEW",
  "COMMIT",
  "TEST_CASES",
  "TEST_RUN",
  "OTHER",
];

export function CollaborationWorkspace() {
  const scope = useActiveScope();
  const { user } = useSession();
  const [bucket, setBucket] = useState<WorkItemBucket>("ALL");
  const [assigned, setAssigned] = useState(false);
  const [mentioned, setMentioned] = useState(false);
  const query = useWorkItems(scope.selection.engagementId, bucket, assigned, mentioned);
  const create = useCreateWorkItem();
  const [showCreate, setShowCreate] = useState(false);
  const [showBulk, setShowBulk] = useState(false);

  return (
    <div>
      <PageHeader
        title="Client work items"
        description="Plan, rank, assign, discuss, execute and approve client work across backlog and delivery months."
      >
        {scope.can("workitem.create") ? (
          <Button onClick={() => setShowCreate((value) => !value)}>
            <Plus className="mr-2 h-4 w-4" />
            Add task
          </Button>
        ) : null}
        {scope.can("workitem.bulk.import") ? (
          <Button variant="outline" onClick={() => setShowBulk((value) => !value)}>
            Bulk upload
          </Button>
        ) : null}
      </PageHeader>
      <div className="space-y-5 p-6">
        <Card>
          <CardContent className="flex flex-wrap items-end gap-4 p-4">
            <Field label="Timeline">
              <select
                aria-label="Task timeline"
                className="h-10 rounded-md border bg-background px-3 text-sm"
                value={bucket}
                onChange={(event) => setBucket(event.target.value as WorkItemBucket)}
              >
                {buckets.map((value) => (
                  <option key={value}>{value}</option>
                ))}
              </select>
            </Field>
            <Toggle
              id="assigned-to-me"
              label="Assigned to me"
              checked={assigned}
              onChange={setAssigned}
            />
            <Toggle
              id="mentioned-to-me"
              label="Mentioned"
              checked={mentioned}
              onChange={setMentioned}
            />
            <p className="text-xs text-muted-foreground">
              {scope.engagement?.name ?? "Select an engagement"} · {query.data?.length ?? 0} tasks
            </p>
          </CardContent>
        </Card>

        {showCreate && scope.engagement?.defaultProjectId ? (
          <CreateWorkItemForm
            engagementId={scope.engagement.id}
            projectId={scope.engagement.defaultProjectId}
            monthId={scope.month?.id ?? null}
            onSubmit={async (input) => {
              await create.mutateAsync(input);
              toast.success("Work item created");
              setShowCreate(false);
            }}
            busy={create.isPending}
          />
        ) : null}
        {showBulk && scope.engagement?.defaultProjectId ? (
          <BulkWorkItemForm
            engagementId={scope.engagement.id}
            projectId={scope.engagement.defaultProjectId}
            monthId={scope.month?.id ?? null}
            onComplete={() => setShowBulk(false)}
          />
        ) : null}

        {query.isPending ? <p>Loading work items…</p> : null}
        {query.error ? (
          <p className="text-sm text-destructive">{query.error.message}</p>
        ) : null}
        <div className="space-y-4">
          {(query.data ?? []).map((item) => (
            <WorkItemCard
              key={item.id}
              item={item}
              currentUserId={user?.id ?? ""}
              permissions={scope.permissions}
            />
          ))}
          {query.data?.length === 0 ? (
            <Card className="border-dashed">
              <CardContent className="grid min-h-40 place-items-center text-sm text-muted-foreground">
                No tasks match this timeline or personal filter.
              </CardContent>
            </Card>
          ) : null}
        </div>
      </div>
    </div>
  );
}

function BulkWorkItemForm({
  engagementId,
  projectId,
  monthId,
  onComplete,
}: {
  engagementId: string;
  projectId: string;
  monthId: string | null;
  onComplete: () => void;
}) {
  const [content, setContent] = useState("");
  const bulk = useCollaborationMutation(collaborationApi.bulkCreate);
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    try {
      const rows = JSON.parse(content) as Array<Partial<CreateWorkItemInput>>;
      if (!Array.isArray(rows) || rows.length < 1 || rows.length > 500) {
        throw new Error("Upload must contain between 1 and 500 task objects.");
      }
      const inputs = rows.map((row) => ({
        engagementId,
        projectId,
        engagementMonthId: row.engagementMonthId ?? monthId,
        workItemCode: row.workItemCode ?? "",
        title: row.title ?? "",
        description: row.description ?? "",
        workflowDescription: row.workflowDescription ?? "",
        acceptanceCriteria: row.acceptanceCriteria ?? "",
        priority: row.priority ?? "P1",
        lifecycleStatus: row.lifecycleStatus ?? (monthId ? "PLANNED" : "BACKLOG"),
        createdOnBehalfOfClient: true,
        links: row.links ?? [],
        assignments: row.assignments ?? [],
      })) satisfies CreateWorkItemInput[];
      await bulk.mutateAsync(inputs);
      toast.success(`${inputs.length} client tasks uploaded`);
      onComplete();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Bulk upload failed");
    }
  };
  return (
    <Card>
      <CardHeader><CardTitle>Bulk upload client tasks</CardTitle></CardHeader>
      <CardContent>
        <form className="space-y-3" onSubmit={(event) => void submit(event)}>
          <Field id="bulk-task-file" label="JSON task file">
            <Input
              id="bulk-task-file"
              type="file"
              accept=".json,application/json"
              onChange={(event) => {
                const file = event.target.files?.[0];
                if (file) void file.text().then(setContent);
              }}
            />
          </Field>
          <Field id="bulk-task-content" label="Task objects">
            <Textarea
              id="bulk-task-content"
              rows={8}
              value={content}
              onChange={(event) => setContent(event.target.value)}
              placeholder={'[{"workItemCode":"TASK_001","title":"Task title","description":"Required description"}]'}
            />
          </Field>
          <p className="text-xs text-muted-foreground">
            One to 500 JSON objects. The active client scope is enforced and every row is recorded as created on behalf of the client.
          </p>
          <Button disabled={!content || bulk.isPending}>
            {bulk.isPending ? "Uploading…" : "Upload tasks atomically"}
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}

function CreateWorkItemForm({
  engagementId,
  projectId,
  monthId,
  onSubmit,
  busy,
}: {
  engagementId: string;
  projectId: string;
  monthId: string | null;
  onSubmit: (input: CreateWorkItemInput) => Promise<void>;
  busy: boolean;
}) {
  const [code, setCode] = useState(`TASK_${Date.now().toString().slice(-6)}`);
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [workflow, setWorkflow] = useState("");
  const [acceptance, setAcceptance] = useState("");
  const [link, setLink] = useState("");
  const submit = (event: FormEvent) => {
    event.preventDefault();
    void onSubmit({
      engagementId,
      projectId,
      engagementMonthId: monthId,
      workItemCode: code,
      title,
      description,
      workflowDescription: workflow,
      acceptanceCriteria: acceptance,
      priority: "P1",
      lifecycleStatus: monthId ? "PLANNED" : "BACKLOG",
      createdOnBehalfOfClient: false,
      links: link
        ? [{ linkType: "DOCUMENT", label: "Task reference", url: link }]
        : [],
      assignments: [],
    });
  };
  return (
    <Card>
      <CardHeader><CardTitle>Create work item</CardTitle></CardHeader>
      <CardContent>
        <form className="grid gap-4 md:grid-cols-2" onSubmit={submit}>
          <Field id="work-item-code" label="Task code"><Input id="work-item-code" value={code} onChange={(e) => setCode(e.target.value)} required /></Field>
          <Field id="work-item-title" label="Title"><Input id="work-item-title" value={title} onChange={(e) => setTitle(e.target.value)} required /></Field>
          <Field id="work-item-description" label="Description"><Textarea id="work-item-description" value={description} onChange={(e) => setDescription(e.target.value)} required /></Field>
          <Field id="work-item-workflow" label="Workflow"><Textarea id="work-item-workflow" value={workflow} onChange={(e) => setWorkflow(e.target.value)} /></Field>
          <Field id="work-item-acceptance" label="Acceptance criteria"><Textarea id="work-item-acceptance" value={acceptance} onChange={(e) => setAcceptance(e.target.value)} /></Field>
          <Field id="work-item-reference" label="PRD, Figma, Jira or other HTTPS link"><Input id="work-item-reference" type="url" value={link} onChange={(e) => setLink(e.target.value)} /></Field>
          <div className="md:col-span-2"><Button disabled={busy}>{busy ? "Creating…" : "Create task"}</Button></div>
        </form>
      </CardContent>
    </Card>
  );
}

function WorkItemCard({
  item,
  currentUserId,
  permissions,
}: {
  item: WorkItem;
  currentUserId: string;
  permissions: string[];
}) {
  const [status, setStatus] = useState<WorkItemStatus>(item.lifecycleStatus);
  const [summary, setSummary] = useState(item.deliverySummary ?? "");
  const [comment, setComment] = useState("");
  const [mentionIds, setMentionIds] = useState("");
  const [hours, setHours] = useState("1");
  const [note, setNote] = useState("");
  const [assignUser, setAssignUser] = useState("");
  const [discipline, setDiscipline] = useState<Discipline>("DEVELOPER");
  const [linkType, setLinkType] = useState<LinkType>("DOCUMENT");
  const [linkUrl, setLinkUrl] = useState("");
  const [linkLabel, setLinkLabel] = useState("");
  const [editTitle, setEditTitle] = useState(item.title);
  const [editDescription, setEditDescription] = useState(item.description);
  const [editWorkflow, setEditWorkflow] = useState(item.workflowDescription);
  const [editAcceptance, setEditAcceptance] = useState(item.acceptanceCriteria);
  const [editPriority, setEditPriority] = useState<WorkItem["priority"]>(item.priority);
  const mutate = useCollaborationMutation(async (action: () => Promise<unknown>) => action());
  const can = (permission: string) => permissions.includes(permission);
  const activeEstimates = useMemo(() => item.estimates.filter((estimate) => !estimate.deleted), [item]);

  const run = async (action: () => Promise<unknown>, message: string) => {
    try {
      await mutate.mutateAsync(action);
      toast.success(message);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Action failed");
    }
  };

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <CardTitle>{item.workItemCode} · {item.title}</CardTitle>
            <p className="mt-1 text-xs text-muted-foreground">
              {item.monthStartDate ?? "Backlog"} · {item.priority}
              {item.stackRank ? ` · rank ${item.stackRank}` : ""}
            </p>
          </div>
          <StatusBadge status={item.lifecycleStatus} />
        </div>
      </CardHeader>
      <CardContent className="space-y-5">
        <div className="grid gap-4 text-sm lg:grid-cols-3">
          <TextBlock label="Description" value={item.description} />
          <TextBlock label="Workflow" value={item.workflowDescription || "Not specified"} />
          <TextBlock label="Acceptance criteria" value={item.acceptanceCriteria || "Not specified"} />
        </div>
        <div className="flex flex-wrap gap-2 text-xs">
          <span className="rounded bg-muted px-2 py-1">Estimate: {item.totalEstimateHours}h</span>
          <span className="rounded bg-muted px-2 py-1">Actual: {item.totalEffortHours}h</span>
          {item.assignments.map((assignment) => (
            <span key={assignment.id} className="rounded bg-muted px-2 py-1">
              {assignment.displayName} · {assignment.discipline}
            </span>
          ))}
        </div>
        {item.links.length ? (
          <div className="flex flex-wrap gap-3">
            {item.links.map((link) => (
              <a key={link.id} href={link.url} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-sm text-primary hover:underline">
                {link.label}<ExternalLink className="h-3 w-3" />
              </a>
            ))}
          </div>
        ) : null}

        {can("workitem.delivery.update") ? (
          <section className="grid gap-3 rounded-md border p-3 md:grid-cols-[200px_1fr_auto]">
            <select aria-label="Delivery status" className="h-10 rounded-md border bg-background px-3" value={status} onChange={(e) => setStatus(e.target.value as WorkItemStatus)}>
              {statuses.map((value) => <option key={value}>{value}</option>)}
            </select>
            <Input aria-label="Delivery summary" value={summary} onChange={(e) => setSummary(e.target.value)} placeholder="Delivery summary" />
            <Button variant="outline" onClick={() => void run(() => collaborationApi.updateStatus(item.id, { expectedVersion: item.version, lifecycleStatus: status, deliverySummary: summary }), "Delivery status updated")}>Update status</Button>
          </section>
        ) : null}

        <div className="grid gap-4 lg:grid-cols-2">
          {can("workitem.comment") ? (
            <ActionPanel title="Comment and tag" icon={<MessageSquareText className="h-4 w-4" />}>
              <Textarea value={comment} onChange={(e) => setComment(e.target.value)} placeholder="Comment" />
              <Input value={mentionIds} onChange={(e) => setMentionIds(e.target.value)} placeholder="Mention user IDs, comma-separated" />
              <Button size="sm" disabled={!comment} onClick={() => void run(() => collaborationApi.addComment(item.id, { body: comment, mentionedUserIds: splitIds(mentionIds) }), "Comment added")}>Post comment</Button>
            </ActionPanel>
          ) : null}
          {can("workitem.assign") ? (
            <ActionPanel title="Assign or claim" icon={<Users className="h-4 w-4" />}>
              <Input value={assignUser} onChange={(e) => setAssignUser(e.target.value)} placeholder="User profile ID" />
              <select aria-label="Discipline" className="h-10 rounded-md border bg-background px-3" value={discipline} onChange={(e) => setDiscipline(e.target.value as Discipline)}>
                {disciplines.map((value) => <option key={value}>{value}</option>)}
              </select>
              <Button size="sm" disabled={!assignUser} onClick={() => void run(() => collaborationApi.addAssignment(item.id, { userProfileId: assignUser, discipline }), "Assignment added")}>Assign</Button>
            </ActionPanel>
          ) : null}
          {can("workitem.estimate") ? (
            <ActionPanel title="Estimate" icon={<Plus className="h-4 w-4" />}>
              <Input type="number" min="0.01" step="0.25" value={hours} onChange={(e) => setHours(e.target.value)} />
              <Input value={note} onChange={(e) => setNote(e.target.value)} placeholder="Estimate note" />
              <Button size="sm" disabled={!currentUserId} onClick={() => void run(() => collaborationApi.addEstimate(item.id, { userProfileId: currentUserId, hours: Number(hours), note }), "Estimate added")}>Add estimate</Button>
              {activeEstimates.map((estimate) => (
                <div key={estimate.id} className="flex items-center justify-between text-xs">
                  <span>{estimate.displayName}: {estimate.hours}h</span>
                  <Button size="icon" variant="ghost" aria-label={`Delete estimate by ${estimate.displayName}`} onClick={() => void run(() => collaborationApi.deleteEstimate(item.id, estimate.id), "Estimate removed")}><Trash2 className="h-3 w-3" /></Button>
                </div>
              ))}
            </ActionPanel>
          ) : null}
          {can("workitem.effort") ? (
            <ActionPanel title="Actual effort" icon={<Plus className="h-4 w-4" />}>
              <Input type="number" min="0.01" max="24" step="0.25" value={hours} onChange={(e) => setHours(e.target.value)} />
              <Input value={note} onChange={(e) => setNote(e.target.value)} placeholder="Effort note" />
              <Button size="sm" disabled={!currentUserId} onClick={() => void run(() => collaborationApi.addEffort(item.id, { userProfileId: currentUserId, workDate: new Date().toISOString().slice(0, 10), hours: Number(hours), note }), "Effort added")}>Log effort</Button>
            </ActionPanel>
          ) : null}
          {can("workitem.update") ? (
            <ActionPanel title="Edit task definition" icon={<Plus className="h-4 w-4" />}>
              <Input aria-label="Edit task title" value={editTitle} onChange={(e) => setEditTitle(e.target.value)} />
              <Textarea aria-label="Edit task description" value={editDescription} onChange={(e) => setEditDescription(e.target.value)} />
              <Textarea aria-label="Edit task workflow" value={editWorkflow} onChange={(e) => setEditWorkflow(e.target.value)} />
              <Textarea aria-label="Edit task acceptance criteria" value={editAcceptance} onChange={(e) => setEditAcceptance(e.target.value)} />
              <select aria-label="Edit task priority" className="h-10 rounded-md border bg-background px-3" value={editPriority} onChange={(e) => setEditPriority(e.target.value as WorkItem["priority"])}>
                {["P0", "P1", "P2", "P3"].map((value) => <option key={value}>{value}</option>)}
              </select>
              <Button size="sm" onClick={() => void run(() => collaborationApi.updateWorkItem(item.id, {
                expectedVersion: item.version,
                title: editTitle,
                description: editDescription,
                workflowDescription: editWorkflow,
                acceptanceCriteria: editAcceptance,
                priority: editPriority,
                engagementMonthId: item.engagementMonthId,
              }), "Task definition updated")}>Save task changes</Button>
            </ActionPanel>
          ) : null}
          {can("workitem.update") ? (
            <ActionPanel title="Add task link" icon={<ExternalLink className="h-4 w-4" />}>
              <select aria-label="Link type" className="h-10 rounded-md border bg-background px-3" value={linkType} onChange={(e) => setLinkType(e.target.value as LinkType)}>
                {linkTypes.map((value) => <option key={value}>{value}</option>)}
              </select>
              <Input value={linkLabel} onChange={(e) => setLinkLabel(e.target.value)} placeholder="Link label" />
              <Input type="url" value={linkUrl} onChange={(e) => setLinkUrl(e.target.value)} placeholder="https://…" />
              <Button size="sm" disabled={!linkLabel || !linkUrl} onClick={() => void run(() => collaborationApi.addLink(item.id, { linkType, label: linkLabel, url: linkUrl }), "Link added")}>Add link</Button>
            </ActionPanel>
          ) : null}
          {can("workitem.plan.approve") || can("workitem.delivery.approve.l1") || can("workitem.delivery.approve.l2") ? (
            <ApprovalPanel item={item} permissions={permissions} run={run} />
          ) : null}
        </div>

        {item.comments.length ? (
          <section>
            <h4 className="mb-2 text-sm font-medium">Conversation</h4>
            <div className="space-y-2">
              {item.comments.map((entry) => (
                <div key={entry.id} className="rounded-md bg-muted/60 p-3 text-sm">
                  <p>{entry.body}</p>
                  <p className="mt-1 text-xs text-muted-foreground">{entry.authorSubject} · {new Date(entry.createdAt).toLocaleString()} · {entry.mentionedUserIds.length} tagged</p>
                </div>
              ))}
            </div>
          </section>
        ) : null}
      </CardContent>
    </Card>
  );
}

function ApprovalPanel({
  item,
  permissions,
  run,
}: {
  item: WorkItem;
  permissions: string[];
  run: (action: () => Promise<unknown>, message: string) => Promise<void>;
}) {
  const stages = [
    permissions.includes("workitem.plan.approve") ? "PLAN_L1" : null,
    permissions.includes("workitem.delivery.approve.l1") ? "DELIVERY_L1" : null,
    permissions.includes("workitem.delivery.approve.l2") ? "DELIVERY_L2" : null,
  ].filter(Boolean) as Array<"PLAN_L1" | "DELIVERY_L1" | "DELIVERY_L2">;
  const [stage, setStage] = useState(stages[0]);
  const [decision, setDecision] = useState<"APPROVED" | "REJECTED" | "CHANGES_REQUESTED">("APPROVED");
  const [rank, setRank] = useState(String(item.stackRank ?? 1));
  const [comment, setComment] = useState("");
  return (
    <ActionPanel title="Client approval" icon={<Users className="h-4 w-4" />}>
      <select aria-label="Approval stage" className="h-10 rounded-md border bg-background px-3" value={stage} onChange={(e) => setStage(e.target.value as typeof stage)}>
        {stages.map((value) => <option key={value}>{value}</option>)}
      </select>
      <select aria-label="Approval decision" className="h-10 rounded-md border bg-background px-3" value={decision} onChange={(e) => setDecision(e.target.value as typeof decision)}>
        <option>APPROVED</option><option>REJECTED</option><option>CHANGES_REQUESTED</option>
      </select>
      {stage === "PLAN_L1" ? <Input type="number" min="1" value={rank} onChange={(e) => setRank(e.target.value)} placeholder="Stack rank" /> : null}
      <Input value={comment} onChange={(e) => setComment(e.target.value)} placeholder="Approval comment" />
      <Button size="sm" onClick={() => void run(() => collaborationApi.approve(item.id, { expectedVersion: item.version, stage, decision, stackRank: stage === "PLAN_L1" ? Number(rank) : null, comment }), "Decision recorded")}>Record decision</Button>
    </ActionPanel>
  );
}

function ActionPanel({ title, icon, children }: { title: string; icon: ReactNode; children: ReactNode }) {
  return <section className="space-y-2 rounded-md border p-3"><h4 className="flex items-center gap-2 text-sm font-medium">{icon}{title}</h4>{children}</section>;
}
function Field({ id, label, children }: { id?: string; label: string; children: ReactNode }) {
  return <div className="space-y-1"><Label htmlFor={id}>{label}</Label>{children}</div>;
}
function Toggle({ id, label, checked, onChange }: { id: string; label: string; checked: boolean; onChange: (value: boolean) => void }) {
  return <label htmlFor={id} className="flex items-center gap-2 text-sm"><Checkbox id={id} checked={checked} onCheckedChange={(value) => onChange(value === true)} />{label}</label>;
}
function TextBlock({ label, value }: { label: string; value: string }) {
  return <div><p className="text-xs uppercase tracking-wide text-muted-foreground">{label}</p><p className="mt-1 whitespace-pre-wrap">{value}</p></div>;
}
function splitIds(value: string) {
  return value.split(",").map((item) => item.trim()).filter(Boolean);
}
