import { useState } from "react";
import { AlertTriangle, ArrowRight, RotateCcw } from "lucide-react";

import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

import { EmptyState } from "./components";
import type {
  CertificationInboxItem,
  CertificationInboxView,
  CertificationOperationsView,
} from "./contracts";
import { formatDateTime, formatLabel } from "./formatting";
import { useReplayCertificationNotification } from "./hooks";

export function CertificationInboxPanel({
  inbox,
  mode = "ALL",
}: {
  inbox: CertificationInboxView;
  mode?: "ALL" | "CONFIRMATION";
}) {
  const items = mode === "CONFIRMATION"
    ? inbox.items.filter(
        (item) =>
          item.confirmationState !== null ||
          item.pendingInboundReviewCount > 0 ||
          item.readinessStatus !== null,
      )
    : inbox.items;

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">
          {mode === "CONFIRMATION" ? "Confirmation work" : "My certification work"}
        </CardTitle>
        <p className="text-sm text-muted-foreground">
          {inbox.actionRequired} action{inbox.actionRequired === 1 ? "" : "s"} required
          {inbox.overdue > 0 ? ` · ${inbox.overdue} overdue` : ""}
          {" · "}server-scoped at {formatDateTime(inbox.generatedAt)}
        </p>
      </CardHeader>
      <CardContent>
        {items.length === 0 ? (
          <EmptyState
            title="No visible work"
            detail="There are no certification or confirmation months in your active server-resolved scope."
          />
        ) : (
          <div className="space-y-3">
            {items.map((item) => (
              <InboxItem key={item.monthId} item={item} />
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function InboxItem({ item }: { item: CertificationInboxItem }) {
  return (
    <article className="rounded-lg border p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="font-medium">
            {item.engagementCode} · {item.engagementName}
          </p>
          <p className="text-sm text-muted-foreground">
            {item.monthLabel} · {item.terminalDecisionCount}/{item.deliverableCount} terminal
            decisions
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <StatusBadge status={item.lifecycleState} />
          {item.submissionStatus && <StatusBadge status={item.submissionStatus} />}
          {item.confirmationState && <StatusBadge status={item.confirmationState} />}
          {item.overdue && <StatusBadge status="OVERDUE" />}
        </div>
      </div>
      <dl className="mt-3 grid gap-2 text-sm sm:grid-cols-3">
        <div>
          <dt className="text-muted-foreground">Assigned reviews</dt>
          <dd className="font-medium">{item.assignedReviewCount}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">Restricted evidence reviews</dt>
          <dd className="font-medium">{item.pendingInboundReviewCount}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">Confirmation due</dt>
          <dd className="font-medium">
            {item.confirmationDueAt ? formatDateTime(item.confirmationDueAt) : "Not requested"}
          </dd>
        </div>
      </dl>
      <div className="mt-4 flex justify-end">
        <Button asChild size="sm" variant={item.nextAction === "NO_ACTION" ? "outline" : "default"}>
          <a href={item.actionPath}>
            {item.nextAction === "NO_ACTION" ? "Open month" : formatLabel(item.nextAction)}
            <ArrowRight aria-hidden="true" />
          </a>
        </Button>
      </div>
    </article>
  );
}

export function CertificationOperationsPanel({
  operations,
}: {
  operations: CertificationOperationsView;
}) {
  const replay = useReplayCertificationNotification();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [reason, setReason] = useState("");

  const submitReplay = () => {
    const item = operations.actionableItems.find((candidate) => candidate.id === selectedId);
    if (!item || reason.trim().length < 3) return;
    replay.mutate(
      {
        notificationId: item.id,
        expectedMonthVersion: item.monthVersion,
        reason: reason.trim(),
      },
      {
        onSuccess: () => {
          setSelectedId(null);
          setReason("");
        },
      },
    );
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Certification operations</CardTitle>
        <p className="text-sm text-muted-foreground">
          Provider: {formatLabel(operations.providerConfiguration)} · durable queue state only;
          transport delivery never implies confirmation.
        </p>
      </CardHeader>
      <CardContent className="space-y-5">
        <div className="grid gap-3 md:grid-cols-3">
          {operations.queues.map((queue) => (
            <div key={queue.queue} className="rounded-lg border p-3 text-sm">
              <p className="font-medium">{formatLabel(queue.queue)}</p>
              <p className="mt-1 text-muted-foreground">
                {queue.pending} pending · {queue.claimed} claimed · {queue.failed} failed ·{" "}
                {queue.deadLetter} dead letter
              </p>
              {queue.oldestActionableAt && (
                <p className="mt-1 text-xs text-muted-foreground">
                  Oldest actionable {formatDateTime(queue.oldestActionableAt)}
                </p>
              )}
            </div>
          ))}
        </div>

        {operations.actionableItems.length === 0 ? (
          <EmptyState
            title="No actionable queue work"
            detail="No pending, claimed, failed, dead-letter, or cancelled work is visible in your operations scope."
          />
        ) : (
          <div className="space-y-3">
            {operations.actionableItems.map((item) => (
              <article key={`${item.queue}-${item.id}`} className="rounded-lg border p-3 text-sm">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div>
                    <p className="font-medium">
                      {item.engagementCode} · {item.monthLabel} · {formatLabel(item.workType)}
                    </p>
                    <p className="text-xs text-muted-foreground">
                      {formatLabel(item.queue)} · attempt {item.attemptCount}
                      {item.dueAt ? ` · due ${formatDateTime(item.dueAt)}` : ""}
                    </p>
                  </div>
                  <StatusBadge status={item.status} />
                </div>
                {item.safeErrorCode && (
                  <p className="mt-2 flex items-center gap-1 text-xs text-destructive">
                    <AlertTriangle aria-hidden="true" className="h-3.5 w-3.5" />
                    {formatLabel(item.safeErrorCode)}
                    {item.correlationId ? ` · reference ${item.correlationId}` : ""}
                  </p>
                )}
                {item.replayAllowed && (
                  <div className="mt-3">
                    {selectedId === item.id ? (
                      <div className="space-y-2 rounded-md bg-muted/30 p-3">
                        <Label htmlFor={`replay-reason-${item.id}`}>Replay reason</Label>
                        <Input
                          id={`replay-reason-${item.id}`}
                          value={reason}
                          maxLength={10_000}
                          onChange={(event) => setReason(event.target.value)}
                          placeholder="Explain why this failed notification is safe to replay"
                        />
                        <div className="flex gap-2">
                          <Button
                            size="sm"
                            onClick={submitReplay}
                            disabled={reason.trim().length < 3 || replay.isPending}
                          >
                            <RotateCcw aria-hidden="true" />
                            Queue replay
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => {
                              setSelectedId(null);
                              setReason("");
                            }}
                          >
                            Cancel
                          </Button>
                        </div>
                      </div>
                    ) : (
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => setSelectedId(item.id)}
                      >
                        <RotateCcw aria-hidden="true" />
                        Replay
                      </Button>
                    )}
                  </div>
                )}
              </article>
            ))}
          </div>
        )}
        {replay.isError && (
          <p role="alert" className="text-sm text-destructive">
            The replay was not queued. Refresh the operations state and verify the month version,
            provider configuration, and token lifecycle.
          </p>
        )}
      </CardContent>
    </Card>
  );
}
