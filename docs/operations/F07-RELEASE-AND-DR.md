# F07 Release, Supply-Chain and Recovery Procedure

Run all commands from the repository root. Generated evidence belongs below
`.f07-evidence/` and must be retained by CI or the approved evidence store; it
is not approval by itself.

```bash
F07_RUN_ID="local-$(date -u +%Y%m%dT%H%M%SZ)-$$"
node scripts/f07/release.mjs schema
node scripts/f07/provenance.mjs \
  --expected-commit "$(git rev-parse HEAD)" \
  --require-clean \
  --input .github/workflows/f07-release-evidence.yml,package.json,package-lock.json,backend/pom.xml,backend/compose.yaml,scripts/f07,backend/src/main/resources/db/migration,docs/features/07-hardening-go-live,dist,backend/target/workflow-backend-0.1.0-SNAPSHOT.jar \
  --output ".f07-evidence/${F07_RUN_ID}/provenance.json"
node scripts/f07/supply-chain.mjs --run \
  --artifact dist,backend/target/workflow-backend-0.1.0-SNAPSHOT.jar \
  --report-dir ".f07-evidence/${F07_RUN_ID}/supply-chain"
node scripts/f07/release.mjs release \
  --ci-bundle ".f07-evidence/${F07_RUN_ID}/bound/ci-evidence-bundle.json"
```

The package-facing supply-chain command preserves this rule: its legacy
`.f07-evidence/supply-chain` argument is automatically expanded beneath a
unique `F07_RUN_ID` (or a generated timestamp/PID/UUID value). Existing
evidence is never overwritten.

The supply-chain command requires Trivy and npm, produces only a sanitized
secret summary, scans Java/Node lock/build inputs for high/critical
vulnerabilities and misconfiguration, runs production npm audit, and emits a
CycloneDX source, artifact and configured-image SBOMs. Checked-in Semgrep rules
provide Java/JavaScript SAST; the license inventory fails on missing, denied or
unapproved licenses. Missing tools, missing artifacts or invalid scanner output
fail closed. Critical/high findings block release. Medium findings also block
unless their exact scanner key has a current owner, risk statement, independent
approver and expiry in `scripts/f07/medium-risk-dispositions.json`.
Every explicitly supplied or Compose-discovered container image must use an
immutable `name@sha256:<64 lowercase hex>` reference; mutable tags are rejected
before Trivy runs.

CI pulls Semgrep 1.135.0 by immutable container digest, verifies the resolved
repository digest and runs it with a read-only repository mount. It does not
install Semgrep or unpinned Python dependencies from PyPI.

Use the load harness only against loopback. Mutations require the explicit
switch and synthetic fixtures:

```bash
node scripts/f07/load-harness.mjs \
  --base-url http://127.0.0.1:8080 \
  --profile scripts/f07/profiles/read-only-smoke.json

node scripts/f07/load-harness.mjs \
  --base-url http://127.0.0.1:8080 \
  --profile scripts/f07/profiles/attendance-26.json \
  --allow-synthetic-mutations
```

The supplied profiles cover the 26-person peak, 10,000-person future burst,
webhook duplicate storm, migration/export mix and package concurrency. The
profiles do not prove database reconciliation, tenant isolation, query plans,
24-hour stability or production headroom by their existence. Load records are
credited only from a signed, commit-bound report with measured requests,
latency and database reconciliation. `F07-PERF-006` and `F07-T057` remain
`ACTION_REQUIRED` until a signed report proves at least 86,400,000 milliseconds,
24 samples, stable resources, bounded queues and zero duplicate/lost
acknowledged effects; a shorter or resumable segment never qualifies.

## Migration and deployment

`migration-preflight.mjs --schema-only` checks stable unique Flyway versions,
checksums, destructive DDL/DML (including column drops/type rewrites and
unqualified mass updates), reviewed expiring exceptions and append-only drift
against the checked-in protected baseline. The mandatory
`F07-CI-MIGRATION-LIVE` lane starts digest-pinned PostgreSQL, bootstraps the
constrained migration role, launches the release application to run Flyway and
requires the live history to exactly equal the source version/checksum history.
Its structured result is bound to the protected base, release commit, isolated
`_f07_preflight` database, canonical suite ID and release provenance.

Before Flyway on a fresh production database, a platform administrator—not the
application—must run `scripts/f07/bootstrap-database-roles.sql` using the
existing `migration_login`. Release preflight checksums that bootstrap;
mandatory CI lanes execute that SQL on new digest-pinned PostgreSQL and run
`F07MigrationBootstrapIT`. The live check proves the current migration login is a member of
`vms_migration_owner` before comparing every live Flyway version/checksum with
the source inventory. The canonical rehearsal report must be in-repository,
bound to the release commit and decision-time-clean provenance, and attach
structured runner output for each allowlisted migrate/validate/compatibility
command. Each proof repeats the command, environment, duration, zero exit code,
stdout/stderr SHA-256 values and release commit; Boolean declarations or opaque
file hashes do not qualify.

Validate canary metrics and rollback evidence with:

```bash
node scripts/f07/rollout-verify.mjs --schema-only
node scripts/f07/rollout-verify.mjs --metrics path/to/canary-metrics.json
node scripts/f07/rollout-verify.mjs --rollback path/to/rollback-evidence.json
```

Canary metrics require numeric request count, error rate, p95, integrity count
and observation duration. Only `ADVANCE` exits zero. Advance only after the full observation window. Any integrity failure aborts
immediately. Rollback disables affected server flags/integrations, verifies the
previous artifact and schema compatibility, preserves new events and
reconciles audit/outbox before resume. Never run a destructive down migration.

## Backup and restore drill

The default command prints a non-mutating plan. Execution is restricted to
explicit loopback databases and new output paths:

```bash
node scripts/f07/backup-drill.mjs

F07_SOURCE_DATABASE_URL='postgresql://USER:PASSWORD@127.0.0.1/fixture_f07_source' \
F07_BACKUP_PASSPHRASE='use-an-approved-24-plus-character-secret' \
F07_BACKUP_PASSPHRASE_ID='backup-passphrase-v1' \
F07_BACKUP_INTEGRITY_KEY='independent-secret-at-least-32-characters' \
F07_BACKUP_INTEGRITY_KEY_ID='backup-integrity-v1' \
F07_SOURCE_OBJECT_ROOT='/absolute/local/object-fixture' \
node scripts/f07/backup-drill.mjs backup --execute \
  --output ".f07-evidence/backups/${F07_RUN_ID}"

F07_DRILL_DATABASE_URL='postgresql://USER:PASSWORD@127.0.0.1/empty_f07_drill' \
F07_CONFIRM_EMPTY_TARGET='empty_f07_drill' \
F07_BACKUP_PASSPHRASE='use-an-approved-24-plus-character-secret' \
F07_BACKUP_PASSPHRASE_ID='backup-passphrase-v1' \
F07_BACKUP_INTEGRITY_KEY='independent-secret-at-least-32-characters' \
F07_BACKUP_INTEGRITY_KEY_ID='backup-integrity-v1' \
F07_DRILL_SIGNING_KEY='separate-approved-24-character-key' \
F07_DRILL_SIGNING_KEY_ID='drill-signing-v1' \
F07_DRILL_OBJECT_ROOT='/absolute/new/object-restore' \
node scripts/f07/backup-drill.mjs restore --execute \
  --input ".f07-evidence/backups/${F07_RUN_ID}" \
  --expected-backup-commit "$(git rev-parse HEAD)" \
  --max-age-hours 24 \
  --assertions path/to/local-restore-assertions.json \
  --output ".f07-evidence/${F07_RUN_ID}/restore-drill.json"
```

The assertion document uses schema version 1, repeats the release commit, and
contains an `assertions` object. Each assertion attaches structured,
allowlisted runner proof for Flyway validation,
package/evidence re-hash, access revalidation, queue checkpoint/resume, legal
holds, transaction-boundary recovery, orphan prevention, duplicate-effect
prevention and explicitly stale provider state. The restore refuses a non-empty database, never issues clean/drop, verifies
encrypted backup and manifest checksums, compares Flyway history, table counts
and object hashes, and signs the drill report. Any missing local assertion keeps
the drill `ACTION_REQUIRED`; provider PITR, production RPO/RTO, regional
failover and quarterly production-like execution always stay `ACTION_REQUIRED`
until external evidence is attached.

The backup manifest is authenticated with HMAC-SHA256 using an independent
integrity key and includes non-secret key/version references, backup UUID,
source descriptor, creation time and release commit. Passphrase, integrity and
report-signing secrets and their identifiers must all be pairwise distinct.
Restore verifies the manifest before decrypting and rejects stale,
future-dated, replayed or wrong-commit backups, rejects symlinked inputs and
unsafe tar paths/links/special members. The append-only replay ledger records
every reserved attempt and final `SUCCESS` or `FAILED` disposition; success can
never be replayed and any retry after a failed attempt needs a signed, scoped,
unexpired `restore-retry-authorization-v1` document passed with
`--retry-authorization`. Before inspecting the ledger or target, restore
atomically creates a deterministic backup/target claim with exclusive-create
semantics. A concurrent process cannot enter restore for the same pair; each
authorized retry receives a distinct append-only attempt after the prior claim
is released.
Decrypted dumps and archives are created exclusively inside random,
owner-private operating-system temporary directories, never inside the supplied
backup directory, and are recursively removed after use.

Release PASS evidence is derived only from canonical CI lanes, not from
task-authored commands or arbitrary file digests. Every local task/test ID has
one checked-in lane and explicit machine-case requirement; overrides are
rejected. Unimplemented or semantically incomplete cases have no aliases and
remain `ACTION_REQUIRED`. In particular, the existing accessibility route/axe
smokes do not satisfy the broader F07 accessibility cases, and mocked feature
journeys do not satisfy E2E-01–E2E-10 real-system contracts.

CI evidence embeds the raw current-run Surefire/Failsafe XML, Vitest JSON,
Playwright JSON or structured operational report. The runner, binder and
release decision each independently parse it. Only exact observed `PASSED`
cases with finite duration qualify; skipped, failed, absent, duplicate,
stale-report and allowlist-only IDs never do. CI also binds the exact allowlisted
commands plus zero exit code and stdout/stderr digests to a checksummed,
decision-time-clean provenance document that includes the workflow, lockfile,
Maven inputs, migrations, release policy documents, digest-pinned compose
image, scripts, built frontend and backend JAR. Playwright evidence is separated
by workforce, delivery, certification, finance, migration and
accessibility/compatibility feature lanes. Performance, signed load, ≥24-hour
soak, backup-destroy-restore-reconcile, canary/rollback and documentation each
have distinct evidence contracts. The decision recomputes the complete
provenance artifact inventory and current tracked/untracked worktree state; it
never trusts a stored `worktreeDirty: false` declaration by itself.

External release approvals and any `VERIFIED` configuration entry require a
canonical JSON evidence reference with SHA-256 plus a verified HMAC signature.
The signed document must name an allowlisted independent role and approver and
bind the decision to the release commit, production environment, approval
dates and a future expiry.

Before production, name release, security, data, operations, support and
rollback approvers; provision OIDC, database roles, secret manager, scanner,
storage, email, observability/on-call and backup/PITR; complete capacity/soak,
UAT/training, provider reconciliation and Procurement acceptance. Until every
item has dated evidence, the release decision is `NO-GO / ACTION_REQUIRED`.
