# Rollback and Recovery Runbook

## Scope and current limitation

This runbook covers the transition from the historical baseline tag to the Java 25/Spring Boot 4.1.0/PostgreSQL system. Supabase/Lovable dependencies and migration files are removed from the working tree; the baseline tag/history and a verified deployment artifact are the only legacy rollback references. It is a plan, not evidence of a completed restore rehearsal. No user-selected staging PostgreSQL target, source export, storage metadata backup, recovery owner, RPO/RTO, or backup location has been supplied.

## Preconditions before any production cutover

1. Record source application version, Java release, Flyway schema version/checksums, deployment artifact digest, flags, and configuration references (never values).
2. Capture and verify a staging source schema/data export and object-storage metadata export; encrypt it and record checksum, location, operator, timestamp, and restore test.
3. Create a PostgreSQL physical/logical backup using the approved platform process; record restore point and verify a restore in an isolated environment.
4. Record migration mapping/reconciliation counts and a go/no-go owner. Do not infer a staging project from `supabase/config.toml`.
5. Keep legacy fixed-cost routes available only while their data source remains safely available and access-controlled.

## Rollback decision tree

| Condition | Action |
|---|---|
| New Java deployment fails before writes | Route traffic back to the last known-good legacy deployment; retain diagnostics; no database rollback required. |
| Java deployment has writes but no irreversible external action | Disable new workflow routes/jobs, preserve PostgreSQL evidence/audit records, restore the previous application version, and investigate. Do not delete newly recorded data. |
| Flyway migration has compatibility issue | Deploy a forward corrective Flyway migration where possible. Restore only from the verified pre-migration backup when approved and when no evidence would be lost. |
| Corruption/security event | Isolate credentials/traffic, preserve logs/audit evidence, invoke incident response, restore to approved point-in-time in a clean environment, and reconcile before reopening. |
| Historical baseline must be restored | Restore the verified pre-cutover deployment artifact produced from commit `5e463c7` / tag `baseline/pre-workforce-20260725` only after verifying its source database is intact. Its historical anonymous policies make it unsuitable for production workforce data. |

## Rollback commands and validation

Exact deployment/restore commands are platform-specific and intentionally not invented. The following repository validation commands must pass before a release candidate is approved:

```bash
npm run sdlc:check
npm run typecheck
npm run lint
npm run test
npm run build
mvn -B -f backend/pom.xml verify       # required after backend scaffold exists
```

After a Java backend exists, additionally run the agreed Flyway validation/migration command against an ephemeral or staging PostgreSQL instance, execute authenticated and cross-tenant API tests, and verify generated `/v3/api-docs`. Never execute destructive `clean` or restore commands against an unresolved environment.

## Required evidence / currently blocked items

| Item | Status | Owner/input required |
|---|---|---|
| Staging source schema/data backup | Blocked | User-selected staging source and approved encrypted destination |
| Storage metadata/object inventory backup | Blocked | Staging source, storage access, retention decision |
| PostgreSQL staging backup/restore rehearsal | Blocked | Provisioned staging PostgreSQL and operations owner |
| Legacy route smoke in staging-like environment | Blocked | Staging URL/test identity and explicit authorization |
| Production project references without credentials | Blocked | Platform/operations owner |

Do not mark F00 operations tests as passed from source inspection.
