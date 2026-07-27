# F05 backend implementation record

Updated: 2026-07-27

## Boundaries

F05 consumes F04 through `effective_f05_certification_handoffs` and the
`f05_handoff_invalidations` trigger contract. It does not mutate F02–F04 source
facts. Organization, engagement, vendor and Procurement authority are resolved
server-side from the authenticated JWT subject and active scoped assignments.
No employee compensation, rate, markup, margin, payroll or allocation-based
commercial calculation is accepted, persisted or returned by this API.

The local provider-neutral boundary is explicit:

- Private artifact metadata, object version, checksum, classification, retention
  class, scan state and lineage are durable F05 facts.
- An unconfigured storage/scanner/renderer/ERP remains `NOT_CONFIGURED` (or an
  artifact remains `UNKNOWN`/blocked). The backend does not synthesize a passed
  scan, stored byte object, rendered output, ERP acceptance or funds movement.
- JSON manifests can be produced locally and downloaded only after recomputing
  their checksum. Provider-backed bytes require the corresponding configured and
  scan-passed lifecycle.

## Implemented architecture

- `FinanceCanonicalJson` performs deterministic property ordering and SHA-256
  hashing for immutable F05 manifests. Package item order remains significant.
- `FinanceMutationJournal` records request-hash idempotency, append-only audit,
  domain events and transactional outbox rows in the same transaction as the
  business mutation.
- `FinancePackageService` consumes only an effective F04 handoff, generates an
  immutable canonical package and item/output lineage, supersedes the prior
  current version, exposes retained history and diffs, verifies local manifest
  integrity before download, and records access.
- `FinanceController` is the `/api/v1/finance` transport boundary. Mutation
  methods require `Idempotency-Key`; versioned mutations require a numeric
  `If-Match` equal to the body expected version. Uploads use multipart `file` and
  JSON `metadata` parts. Downloads return attachment responses and never expose
  provider credentials or signed URLs in JSON.

## Verification record

Incremental compilation checkpoints use:

```text
mvn -B -f backend/pom.xml -DskipTests compile
```

The final local gate is:

```text
mvn -B -f backend/pom.xml verify
```

G0–G3 require the automated catalog in `TEST_CASES.md`, including Flyway from
empty PostgreSQL, authorization/non-disclosure, immutable lineage, deterministic
hashes, idempotency/concurrency, audit/outbox, controller contract, F01–F04
regression, UI/accessibility and export safety. A successful compilation alone
does not close those gates.

G4 remains external: approved private versioned storage, malware scanning and
quarantine callbacks, rendering, retention/legal hold, deployment grants,
backup/restore, SSO policy, Procurement process acceptance and ERP/AP integration
when enabled. Local metadata-only behavior and fixtures cannot close G4.
