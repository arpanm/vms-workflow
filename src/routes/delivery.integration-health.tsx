import { createFileRoute } from "@tanstack/react-router";
import { AlertTriangle, Clock3, Link2, ListRestart, ShieldAlert } from "lucide-react";
import { useState } from "react";

import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { useSession } from "@/features/auth/session-provider";
import {
  useCommitmentDeadLetters,
  useCommitmentReplay,
  useLinearHealth,
  useLinearReconciliation,
} from "@/features/delivery/hooks";
import { DeliveryQueryBoundary } from "@/features/delivery/query-boundary";
import { DeliveryEngagementScope } from "@/features/delivery/scope";
import {
  connectionStatusPresentation,
  providerRegistrationPresentation,
} from "@/features/delivery/presentation";
import { requireDeliveryRoute } from "@/lib/delivery-route";

export const Route = createFileRoute("/delivery/integration-health")({
  beforeLoad: requireDeliveryRoute,
  head: () => ({ meta: [{ title: "Linear integration health — Cadence" }] }),
  component: IntegrationHealthPage,
});

function IntegrationHealthPage() {
  return (
    <div>
      <PageHeader
        title="Linear integration health"
        description="Provider readiness, freshness and durable queue visibility without exposing credentials or triggering page-load polling."
      />
      <div className="p-6">
        <DeliveryEngagementScope>
          {(engagementId) => <Health engagementId={engagementId} />}
        </DeliveryEngagementScope>
      </div>
    </div>
  );
}

function Health({ engagementId }: { engagementId: string }) {
  const { user } = useSession();
  const query = useLinearHealth(engagementId);
  const health = query.data;
  const reconciliation = useLinearReconciliation(engagementId);
  const canReplayCommitments = Boolean(
    user?.permissions.includes("delivery.commitment.replay"),
  );
  const deadLetters = useCommitmentDeadLetters(engagementId, canReplayCommitments);
  const commitmentReplay = useCommitmentReplay(engagementId);
  const [reason, setReason] = useState("");
  const [key, setKey] = useState(() => crypto.randomUUID());
  const [outcome, setOutcome] = useState<"AVAILABLE" | "UNAVAILABLE">("AVAILABLE");
  const [replayTarget, setReplayTarget] = useState<string | null>(null);
  const [replayReason, setReplayReason] = useState("");
  const [replayKey, setReplayKey] = useState(() => crypto.randomUUID());

  function recordReconciliation(event: React.FormEvent) {
    event.preventDefault();
    if (!health?.connectionId || !reason.trim()) return;
    reconciliation.mutate(
      {
        connectionId: health.connectionId,
        input:
          outcome === "AVAILABLE"
            ? { outcome, reason: reason.trim() }
            : {
                outcome,
                errorCode: "PROVIDER_UNAVAILABLE",
                reason: reason.trim(),
              },
        idempotencyKey: key,
      },
      {
        onSuccess: () => {
          setReason("");
          setKey(crypto.randomUUID());
        },
      },
    );
  }

  function replayCommitment(event: React.FormEvent) {
    event.preventDefault();
    if (!replayTarget || !replayReason.trim()) return;
    commitmentReplay.mutate(
      {
        outboxId: replayTarget,
        input: { reason: replayReason.trim() },
        idempotencyKey: replayKey,
      },
      {
        onSuccess: () => {
          setReplayTarget(null);
          setReplayReason("");
          setReplayKey(crypto.randomUUID());
        },
      },
    );
  }
  return (
    <DeliveryQueryBoundary queries={[query]}>
      {health && (
        <div className="space-y-4">
          {health.status !== "CONNECTED" && (
            <Card className="border-warning/40 bg-warning/5">
              <CardContent className="flex gap-3 py-4 text-sm">
                <ShieldAlert className="h-5 w-5 shrink-0 text-warning" />
                <div>
                  <p className="font-medium">
                    Provider is {health.status.toLowerCase().replace("_", " ")}
                  </p>
                  <p className="mt-1 text-muted-foreground">
                    {connectionStatusPresentation(health.status)}
                  </p>
                </div>
              </CardContent>
            </Card>
          )}
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
            <HealthCard
              icon={Link2}
              label="Connection"
              value={<StatusBadge status={health.status} />}
              detail={`${providerRegistrationPresentation(
                health.providerRegistrationStatus,
              )}. ${connectionStatusPresentation(health.status)}`}
            />
            <HealthCard
              icon={Clock3}
              label="Last reconciled"
              value={health.lastReconciledAt ?? "Never"}
              detail={`Last verified webhook: ${health.lastVerifiedDeliveryAt ?? "Never"}`}
            />
            <HealthCard
              icon={AlertTriangle}
              label="Stale issues"
              value={health.staleIssueCount}
              detail={`${health.linkedIssueCount} linked`}
            />
            <HealthCard
              icon={ListRestart}
              label="Queue / dead-letter"
              value={`${health.queuedCount} / ${health.deadLetterCount}`}
              detail="Operator review required"
            />
          </div>
          {health.lastError && (
            <Card className="border-destructive/30">
              <CardHeader>
                <CardTitle className="text-base">Last sanitized error</CardTitle>
              </CardHeader>
              <CardContent className="text-sm text-muted-foreground">
                {health.lastError}
              </CardContent>
            </Card>
          )}
          {health.connectionId && health.status !== "NOT_CONFIGURED" && (
            <Card>
              <CardHeader>
                <CardTitle className="text-base">Record reconciliation result</CardTitle>
              </CardHeader>
              <CardContent>
                <form className="space-y-3" onSubmit={recordReconciliation}>
                  <p className="text-sm text-muted-foreground">
                    This writes an authorized, idempotent local reconciliation record. It does not
                    call Linear from the browser or claim a live provider refresh.
                  </p>
                  <label className="flex items-center gap-2 text-sm">
                    <input
                      checked={outcome === "AVAILABLE"}
                      name="outcome"
                      onChange={() => setOutcome("AVAILABLE")}
                      type="radio"
                    />
                    Provider evidence available
                  </label>
                  <label className="flex items-center gap-2 text-sm">
                    <input
                      checked={outcome === "UNAVAILABLE"}
                      name="outcome"
                      onChange={() => setOutcome("UNAVAILABLE")}
                      type="radio"
                    />
                    Provider unavailable (marks retained issue truth stale)
                  </label>
                  <Textarea
                    aria-label="Reconciliation reason"
                    maxLength={4000}
                    onChange={(event) => setReason(event.target.value)}
                    placeholder="Record the provider check, source and operator rationale."
                    required
                    value={reason}
                  />
                  {reconciliation.error && (
                    <p className="text-sm text-destructive">
                      {reconciliation.error.message}
                    </p>
                  )}
                  <Button disabled={reconciliation.isPending || !reason.trim()} type="submit">
                    {reconciliation.isPending ? "Recording…" : "Record reconciliation"}
                  </Button>
                </form>
              </CardContent>
            </Card>
          )}
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Commitment delivery dead letters</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              {!canReplayCommitments ? (
                <p className="text-sm text-muted-foreground">
                  This recovery queue is available only to operators with the
                  <code className="ml-1">delivery.commitment.replay</code> permission.
                  The server remains authoritative for every list and replay request.
                </p>
              ) : (
                <DeliveryQueryBoundary queries={[deadLetters]}>
                  <p className="text-sm text-muted-foreground">
                    Replays preserve the original dead-lettered record and queue a separate,
                    provider-neutral frozen commitment. This does not configure or send through
                    a live email provider.
                  </p>
                  {!deadLetters.data?.length && (
                    <p className="text-sm text-muted-foreground">No dead-lettered commitments.</p>
                  )}
                  {deadLetters.data?.map((item) => (
                    <div className="rounded-md border p-3 text-sm" key={item.outboxId}>
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <span className="font-medium">
                          v{item.planVersion} {item.messageType.toLowerCase()} commitment
                        </span>
                        <Button
                          onClick={() => setReplayTarget(item.outboxId)}
                          size="sm"
                          variant="outline"
                        >
                          Queue replay
                        </Button>
                      </div>
                      <p className="mt-1 text-xs text-muted-foreground">
                        Attempts: {item.attemptCount} · Error: {item.lastErrorCode ?? "Not recorded"}
                        {" · "}Prior replays: {item.replayCount}
                      </p>
                    </div>
                  ))}
                  {replayTarget && (
                    <form className="space-y-2 rounded-md border border-warning/40 p-3" onSubmit={replayCommitment}>
                      <p className="text-sm font-medium">Record replay rationale</p>
                      <Textarea
                        aria-label="Commitment replay rationale"
                        maxLength={4000}
                        onChange={(event) => setReplayReason(event.target.value)}
                        placeholder="Why is this exact dead letter safe to replay?"
                        required
                        value={replayReason}
                      />
                      {commitmentReplay.error && (
                        <p className="text-sm text-destructive">{commitmentReplay.error.message}</p>
                      )}
                      <div className="flex gap-2">
                        <Button
                          disabled={commitmentReplay.isPending || !replayReason.trim()}
                          type="submit"
                        >
                          {commitmentReplay.isPending ? "Queueing…" : "Queue immutable replay"}
                        </Button>
                        <Button
                          onClick={() => setReplayTarget(null)}
                          type="button"
                          variant="ghost"
                        >
                          Cancel
                        </Button>
                      </div>
                    </form>
                  )}
                </DeliveryQueryBoundary>
              )}
            </CardContent>
          </Card>
        </div>
      )}
    </DeliveryQueryBoundary>
  );
}

function HealthCard({
  icon: Icon,
  label,
  value,
  detail,
}: {
  icon: typeof Link2;
  label: string;
  value: React.ReactNode;
  detail: string;
}) {
  return (
    <Card>
      <CardContent className="p-4">
        <Icon className="h-4 w-4 text-muted-foreground" />
        <p className="mt-3 text-xs uppercase tracking-wide text-muted-foreground">{label}</p>
        <div className="mt-1 font-semibold">{value}</div>
        <p className="mt-1 text-xs text-muted-foreground">{detail}</p>
      </CardContent>
    </Card>
  );
}
