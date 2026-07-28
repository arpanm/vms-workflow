# F06 — Migration Operations Runbook

## Quarantined or failed source

Do not parse, preview, download broadly or commit. Record the scan/error code,
correlation ID and source hash. A new clean upload creates a new source/job;
never replace bytes.

## Validation/dependency failure

Export the redacted formula-safe report, fix the named template/key or approved
mapping and re-upload/reprocess. Do not waive a required master silently.

## Worker interruption

Inspect lease, checkpoint, retry count and outbox. Let an expired lease be
reclaimed; replay only through the authorized endpoint. Verify one business
effect per job/row/idempotency key.

The recovery worker is opt-in through `VMS_MIGRATION_WORKER_ENABLED=true`.
It handles only aged/expired scan, parse and validation work; it never claims
commit. Use `POST /api/v1/migrations/jobs/{id}/retry` with the current ETag,
an operator reason and a new idempotency key for manual recovery. At ten
attempts the job is dead-lettered and requires investigation. A cancelled job
is never reopened: retry creates a linked append-only replay job.

Watch the migration age, pending scan, retry, dead-letter, outcome, latency,
row-throughput, reconciliation mismatch and authorization-denial metrics.
Metric labels contain only controlled operation/template/outcome values, never
actors, filenames, natural keys or source rows.

## Reconciliation mismatch

Block approval/commit. Recompute from immutable source/staging and canonical
counts, create a new report version/hash and discard stale sign-offs.

## Incorrect committed batch

If no downstream snapshot/approval/package consumed it, request governed
compensation. Otherwise deny hard rollback and use the normal reopen/new-version
workflow. Source, job, provenance and audit remain immutable.
Compensation must run only through the service-created `COMPENSATE` rollback
action; an arbitrary database session flag is not sufficient authorization.

## Production release

Require source-owner mapping approval, controlled private storage/scanner,
masked capacity rehearsal, backup/restore checkpoint, operational window and
signed reconciliation. Until then the production gate is `ACTION_REQUIRED`.
