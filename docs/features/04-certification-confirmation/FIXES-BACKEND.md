# F04 backend remediation evidence

Date: 2026-07-26

Scope: the provider-neutral Java/Spring/PostgreSQL F04 vertical, including
database invariants, authorization, immutable lifecycle facts, durable local
workers, API contracts, security controls, and backend automation. Live email,
mailbox, object-storage, SSO, and F05 consumer acceptance are not claimed.

## Exact disposition

The review universe contains 46 finding IDs:

- **42 resolved locally**
- **2 partially resolved and still open:** `F04-SEC-008`, `F04-SEC-012`
- **2 open deployment controls:** `F04-BE-016`, `F04-SEC-009`

No other backend review finding remains open. Hardening is additive in
`V12__certification_confirmation_hardening.sql`; the pre-existing V11 migration
was not rewritten during remediation.

## Resolved product and analysis findings

| Finding IDs | Resolution | Executable evidence |
| --- | --- | --- |
| `F04-BE-001`, `F04-SEC-001` | Added database-enforced tenant/month/source lineage, criterion evidence ownership, immutable issued-request scope, and negative direct-SQL coverage. | `CertificationPersistenceIT` |
| `F04-BE-002`, `F04-TEST-001` | Invalidations remain immutable. Effective clearing is represented by an append-only resolution fact; closure and reopen facts are guarded and versioned. | `CertificationPersistenceIT`, `CertificationGovernanceIT` |
| `F04-BE-003`, `F04-SEC-002` | Enforced captured separation of duties against submitter, vendor ownership/party, and service identities, with separately authorized governance paths. | `CertificationWorkflowIT`, `CertificationGovernanceIT` |
| `F04-BE-004`, `F04-SEC-003` | Bound every confirmation contribution to the exact eligible project and current party-scoped authority. | `BusinessConfirmationIT` |
| `F04-BE-005`, `F04-SEC-005`, `F04-TEST-002` | Captured due and token TTL from the immutable policy, enforced `now >= due/expiry`, made expiry durable/idempotent, and preserved denied-token non-consumption. | `BusinessConfirmationIT`, `CertificationClockIT`, `CertificationOperationsWorkerIT` |
| `F04-BE-006` | Separated action facts from terminal outcome notifications. Only the atomic terminal quorum transition emits the terminal event. | `BusinessConfirmationIT`, `CertificationConcurrencyIT` |
| `F04-BE-007`, `F04-SEC-006` | Added encrypted server-only token handoff, a recorded provider-neutral adapter, durable claims/attempts, bounded retry, dead-letter, authorized replay generations, reminders, and expiry jobs. Plaintext tokens are never persisted. | `CertificationOperationsWorkerIT`, `CertificationConcurrencyIT` |
| `F04-BE-008`, `F04-SEC-007` | Added typed APIs/services for signed normalized inbound metadata, restricted review, write-only manual evidence, distinct second review, close, reopen decisions, append-only invalidation resolution, notification replay, and conflict governance. Raw MIME/artifact bytes are not returned. | `CertificationReviewIT`, `CertificationGovernanceIT`, `CertificationOpenApiIT` |
| `F04-BE-009` | Enforced frozen evidence policy per mandatory criterion and added an exact-scoped, immutable, separately authorized evidence-exception path. | `CertificationWorkflowIT`, `CertificationPolicyIT`, `CertificationPersistenceIT` |
| `F04-BE-010` | Added policy-scoped attendance exceptions and included all material readiness sources in canonical input manifests and idempotent run derivation. | `CertificationPolicyIT`, `CertificationWorkflowIT` |
| `F04-BE-011` | Persisted a versioned F05 readiness contract and durable publish job in the terminal confirmation transaction, with retry/attempt lineage and effective status. | `CertificationOperationsWorkerIT`, `CertificationConcurrencyIT`, `F04RegressionIT` |
| `F04-BE-012`, `F04-SEC-013` | Added a dedicated F04 Testcontainers/MockMvc/unit suite, including committed concurrency, deterministic deadline boundaries, security denials, migrations, workers, lifecycle, and F01-F03 regression. | 58 F04 backend tests; full `mvn clean verify` |
| `F04-BE-013` | Added one-way immutable policy versioning: the prior active version may only become superseded as a new version is appended. | `CertificationPolicyIT`, `CertificationPersistenceIT` |
| `F04-BE-014` | Deduplicated addresses across To/CC while retaining all role reasons and persisted immutable template key/version, rendered bodies, recipient snapshot, and archive/body hashes. | `BusinessConfirmationIT`, `CertificationOperationsWorkerIT` |
| `F04-BE-015`, `F04-SEC-011` | Added request-bound normalized correlation IDs to runtime and OpenAPI success/error contracts, allowlisted/redacted problem details, and safe internal unexpected-error logging. Framework 415 behavior is preserved. | `CertificationOpenApiIT`, `CertificationSecurityHardeningIT`, `DeliveryLinearIT` |
| `F04-ANL-001`, `F04-SEC-004` | Full request details require engagement-wide visibility. Project readers receive only their contribution plus a pseudonymous/redacted aggregate with no recipient, source, diff, notification, or hidden-action disclosure. | `BusinessConfirmationIT` |
| `F04-ANL-002` | Made project contribution explicit and project-authorized, including one immutable contribution per eligible request/project for shared owners. | `BusinessConfirmationIT`, `CertificationConcurrencyIT` |
| `F04-ANL-003` | Added `CONFLICT_REVIEW`, immutable conflict/governance facts, separately authorized resolution, and suppression of ordinary quorum completion while conflicted. | `CertificationGovernanceIT` |
| `F04-ANL-004` | Added criterion-owned evidence lineage, unique association semantics, cross-scope database guards, and criterion-specific readback. | `CertificationPersistenceIT`, `CertificationWorkflowIT` |
| `F04-ANL-005` | The production month DTO now builds a server-authoritative confirmation preview from the same captured recipient, eligibility, scope, policy, and due sources used by request creation. | `CertificationWorkflowIT`, `CertificationOpenApiIT` |
| `F04-ANL-006` | Readiness CTAs use the agreed application paths for certification review and confirmation/request journeys. | `CertificationWorkflowIT`; frontend F04 route-contract lane |
| `F04-ANL-007` | Canonical hashing now sorts schema-defined set-like identifier collections while preserving explicitly ordered business collections. | `CanonicalEvidenceHasherTest`, `CertificationPersistenceIT` |
| `F04-ANL-008` | Replaced per-deliverable hydration with bounded bulk queries and a 200-deliverable guard. Query count no longer grows with deliverable count. | `CertificationWorkspaceScaleIT` |
| `F04-SEC-010` | Added fail-safe redacted security events for authentication/authorization denials, token failures, inbound verification, rate limits, and other sensitive boundaries. Actor identity is hashed. | `CertificationReviewIT`, `CertificationSecurityHardeningIT` |

## Resolved test-review findings

All nine backend test-review findings are closed:

| Finding IDs | Resolution |
| --- | --- |
| `F04-TEST-001` | Tests immutable invalidations and append-only effective resolution instead of a forbidden update. |
| `F04-TEST-002` | Separates correction, expiry, token consumption, and replay assertions and checks exact state/effects. |
| `F04-TEST-003` | Adds non-transactional committed concurrency with independent simultaneous requests and exactly-once assertions. |
| `F04-TEST-004` | Drives a terminal confirmation and durable local F05 handoff while proving no invoice/package/procurement or upstream mutation. |
| `F04-TEST-005` | Adds local identity/project/SOD/token/rate/inbound/manual/worker/close/reopen/security-event matrices. Deployment-role proof remains tracked only by `F04-BE-016`/`F04-SEC-009`. |
| `F04-TEST-006` | Uses grouped assertions with fresh savepoints so every SQL invariant executes. |
| `F04-TEST-007` | Structurally checks every operation's correlation contract and verifies normalized runtime success/error propagation. |
| `F04-TEST-008` | Injects mutable fixed clocks and proves one nanosecond before, exactly at, and after due/token expiry boundaries. |
| `F04-TEST-009` | Renames narrow tests and adds the missing scope/inbound boundary coverage. |

## Findings still open

| Finding | Status | Completed locally | Remaining acceptance/control |
| --- | --- | --- | --- |
| `F04-SEC-008` | **Partial — open** | Mandatory evidence policy, scan-status/scope checks, criterion lineage, immutable authorized exceptions, and fail-closed artifact access are enforced. | Approved ingestion/scanning service, MIME-by-content and size/name/host controls, object storage, short-lived project-scoped access URLs, retention, and download audit must be implemented and accepted in the controlled storage environment. |
| `F04-BE-016` | **Open** | API responses minimize or omit restricted contact/raw evidence and restricted operations are authorization/audit bounded. | Production database identities, grants/default-deny policy, restricted-reader mapping, and direct role tests require the deployment database/security-owner design. |
| `F04-SEC-009` | **Open** | Raw fields are minimized/write-only at the API boundary; immutable metadata and redacted security/audit events exist. | Separate migration/runtime/worker/audit-reader identities, RLS or equivalent grants, artifact-prefix policy, and enforced retention/legal-hold/deletion proof remain deployment controls. |
| `F04-SEC-012` | **Partial — open** | Confirmation actions have per-identity/IP limits with `429`/`Retry-After`; bearer-only requests cannot be authenticated by ambient cookies, and denials are audited. | Final strict CORS/origin and future BFF CSRF architecture, edge CSP/HSTS/referrer/permissions headers, production secret-manager enforcement, pinned images, and CI SBOM/SCA/SAST/secret/container gates remain platform work. |

These four IDs are not counted as resolved. In particular, the passing local
suite is not evidence for production DB grants, live object storage, or edge
security configuration.

## Final backend gates

```text
mvn clean verify
PASS

Unit tests:        2 passed, 0 failed, 0 errors, 0 skipped
Integration tests: 105 passed, 0 failed, 0 errors, 0 skipped
Total:             107 passed, 0 failed, 0 errors, 0 skipped

F04 tests:          58 passed (2 unit + 56 integration)
Legacy regressions: 49 passed

git diff --check -- backend/src/main backend/src/test
PASS
```

The clean Testcontainers run validates all 16 migrations, including additive
V12 hardening, against PostgreSQL 18.4.

## External acceptance gates

- Approved email provider/sender domain and dedicated mailbox; live callback
  signing, retry/dead-letter operations, reply/spoof/ambiguity exercises, and
  retention approval.
- Approved evidence ingestion, malware scanning, object storage, restricted
  viewing/download audit, and signed access.
- Production SSO/OTP/MFA/step-up policy and tenant recipient/delegation/quorum
  policy approval.
- A deployed F05 consumer. F04 proves only the durable, versioned local
  readiness handoff and does not create invoice/package/procurement facts.
- A non-intercepted browser-to-Java-to-PostgreSQL system lane. The frontend and
  backend contract lanes pass independently but do not substitute for that
  controlled-environment acceptance.
