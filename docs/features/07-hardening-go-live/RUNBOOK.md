# F07 — Runbook Index

F07 operational procedures are centralized to avoid divergent copies:

- [Incident and workflow runbooks](../../operations/F07-RUNBOOKS.md) — RB-01
  through RB-15 for access, workforce, providers, migration, evidence,
  finance, backup/DR, security and privacy.
- [Release, supply-chain and recovery procedure](../../operations/F07-RELEASE-AND-DR.md)
  — evidence, migration, canary, rollback, supply chain and backup/restore.
- [Machine-readable runbook catalog](runbook-catalog.json) — stable IDs,
  owners, detections and section anchors.
- [Observability policy](observability-policy.json) — bounded labels, alerts,
  thresholds, owners and runbook links.
- [Release evidence](release-evidence.json) and
  [required inventory](required-evidence-inventory.json) — current fail-closed
  decision inputs.

Runbook ownership strings are placeholders until named production owners supply
dated approval evidence. Treat every production/provider/legal record without
that evidence as `ACTION_REQUIRED`.

## Worker deployment invariants

- API: use the production API profile only; all three worker switches remain
  false.
- Certification: deploy `worker-certification` with a distinct LOGIN inheriting
  only `vms_job_worker`.
- Finance: deploy `worker-finance` with a different LOGIN inheriting only
  `vms_job_worker`.
- Migration: deploy `worker-migration` with a LOGIN inheriting only
  `vms_migration_processor`.
- Never grant a worker `vms_app_runtime` or `vms_migration_owner`. Startup
  intentionally fails for a wrong login, missing expected capability, extra
  runtime capability, schema creation, Flyway mutation or migration ownership.
- A migration recovery failure must leave or clear only its own random lease;
  do not bypass `f07_migration_leased_source` with table grants.

Reference non-web deployments: `backend/deploy/f07-workers.yaml`.
