# F04 Certification and Confirmation — Security Analysis

## 2026-07-30 local artifact boundary

The browser does not retain uploaded bytes in component or query mutation state. The server enforces a 25 MiB bound, sanitized non-public object name, immutable UUID path, extension/signature allowlisting, SHA-256 at upload and scan, executable/EICAR rejection, authorization before upload/scan, and scan-passed-only selection. PostgreSQL permits only a consistent one-way terminal scan transition, and the new trigger function is owned by the migration capability with public/worker execution revoked. Transaction rollback performs best-effort private-object cleanup; reconciliation remains the operational backstop for filesystem failure.

**Review date:** 26 July 2026
**Scope:** F04 Java/React implementation, V11 schema, configuration, F04 task/test/review evidence, and PRD 14. This is a static and test-evidence review only; no live provider, mailbox, token, database, or external-system mutation was attempted.

## Outcome

F04 is **not ready for the local security gates G1–G3**. Its useful safeguards are an authenticated JWT API, server-side scope lookups, parameterized JDBC, 256-bit opaque confirmation tokens that are PBKDF2-hashed and not returned by the API, React's normal escaped rendering, and provider-neutral adapters that do not send mail. Those safeguards do not close the P1 authorization, data-isolation, lifecycle, audit, and least-privilege gaps listed in [SECURITY_ISSUES.md](SECURITY_ISSUES.md).

The review found one material new gap beyond the existing F04 reviews: a caller with read access to only one project can retrieve a confirmation request for that month and receive the full recipient list, all confirmation actions/comments, notifications, and month-wide request lineage. This is a project-scope disclosure, not merely a hidden-control/UI issue.

## Threat model

| Asset / decision | Plausible attacker or failure mode | Required boundary |
|---|---|---|
| Tenant/project delivery evidence and confirmation metadata | Authenticated user with a valid but wrong project/engagement scope; direct database writer | Server and database derive organization, engagement, project, and object lineage; inaccessible data is non-disclosing. |
| Certification / business confirmation authority | Dual-hatted vendor/client user, service account, revoked user, or replaying caller | Current active, scoped, human eligible authority; separation of duties; expected-version and idempotency checks. |
| Secure confirmation action | Stolen/forwarded token, replay, deadline bypass, brute-force/CPU flooding | High-entropy opaque token hash, identity binding, captured request policy, expiry, single use, rate limiting, security audit. |
| Inbound/manual email evidence | Spoofed provider callback, forged/forwarded/replayed MIME, malicious attachment | Signed provider callback, exact thread/request match, active eligible sender, scan/quarantine, restricted second review. |
| Restricted contact/email/evidence data | Ordinary runtime/database role, logs/API/UI, operator replay abuse | Classification, least privilege, redaction, audit access, retention/legal-hold enforcement. |
| Immutable evidence, readiness, outbox, and F05 fact | Direct SQL mutation, concurrent worker/action, retry/replay, operator error | Cross-scope database constraints, append-only lineage, durable worker claim/replay controls, integrity/audit/security events. |

## What the implementation gets right

- `/api/**` is authenticated and the decoder constrains tokens to RS256, configured issuer, audience, and standard time validation ([SecurityConfig.java](../../../backend/src/main/java/com/vms/workflow/security/SecurityConfig.java#L25-L58)). The application does not trust a role/tenant submitted by the browser.
- F04 authorization normally resolves active memberships and scoped permissions server-side ([CertificationAuthorizationService.java](../../../backend/src/main/java/com/vms/workflow/security/CertificationAuthorizationService.java#L40-L164)); denial paths use a generic not-found response for these checks.
- Confirmation tokens use 32 random bytes, per-token 24-byte salts, PBKDF2-HMAC-SHA-256 with a minimum 100,000 work factor, constant-time comparison, and a consumed marker ([ConfirmationTokenCodec.java](../../../backend/src/main/java/com/vms/workflow/application/ConfirmationTokenCodec.java#L13-L74), [V11](../../../backend/src/main/resources/db/migration/V11__certification_confirmation_local.sql#L430-L450)). The plaintext is deliberately neither persisted nor returned ([BusinessConfirmationService.java](../../../backend/src/main/java/com/vms/workflow/application/BusinessConfirmationService.java#L181-L195)).
- The secure-token path additionally requires the current authenticated eligible subject; a forwarded link alone does not carry authority ([BusinessConfirmationService.java](../../../backend/src/main/java/com/vms/workflow/application/BusinessConfirmationService.java#L242-L279)).
- F04 uses prepared JDBC parameters. The reviewed F04 calls do not concatenate user-controlled SQL identifiers/values. React renders text normally rather than raw HTML, and its access-token provider is memory-only rather than `localStorage` ([access-token.ts](../../../src/lib/auth/access-token.ts#L1-L25)).
- The implemented provider-neutral email and artifact adapters fail closed with `NOT_CONFIGURED`/`ACTION_REQUIRED` and do not send email or issue object URLs ([ProviderNeutralCertificationEmailAdapter.java](../../../backend/src/main/java/com/vms/workflow/application/ProviderNeutralCertificationEmailAdapter.java#L16-L25), [ProviderNeutralEvidenceArtifactAdapter.java](../../../backend/src/main/java/com/vms/workflow/application/ProviderNeutralEvidenceArtifactAdapter.java#L18-L27)). This is correct deferral behavior, not delivery evidence.

## PRD 14 and F04 control assessment

| Control area | Assessment | Evidence / consequence |
|---|---|---|
| Authentication and disabled-user checks | Partial | JWT validation and active membership checks are present, but service-account type, step-up/MFA, session revocation, and an explicit cookie/BFF policy are not implemented. |
| Tenant/object/project authorization and non-disclosure | Blocked | F04 has server checks, but confirmation action scope is too broad and confirmation reads leak month-wide data to project-scoped readers (F04-SEC-003/004). Database cross-scope invariants are incomplete (F04-SEC-001). |
| SOD and service identity | Blocked | Policy captures SOD but decision paths do not enforce it; no principal class prevents a service identity from approval/certification use (F04-SEC-002). |
| Confirmation links, expiry, replay, CSRF, rate limiting | Blocked | Entropy/hash/single-use primitives are sound, but request due time and captured policy TTL are bypassed, no dispatcher exists, and no F04 rate limiter/security-event path exists (F04-SEC-005/006/010). CSRF is acceptable only while the API stays bearer-only. |
| Inbound mail, MIME, attachments | Blocked locally; live acceptance external | The schema anticipates safe states, but there is no local signed inbound ingestion/review/scan worker. Provider/mailbox selection and real callback evidence are external gates; the missing fake/recorded local vertical is not. |
| PII, restricted email, redaction, API/UI | Blocked | Raw verified emails and manual-evidence sender/recipients/subject are persisted without demonstrated role/grant/RLS protection, retention enforcement, or restricted-access audit. Project-scope request read also exposes recipients/actions. |
| Database least privilege / direct SQL / immutability | Blocked | V11 has helpful append-only triggers, but gaps allow cross-scope summary/request facts and request-scope mutations. No F04 database roles, grants, RLS, or migration/runtime account separation is shown. |
| Input/output XSS, URL and date handling | Partial | DTO length/type constraints, React escaping, URL encoding and a server `@Future` due date are positive. F04 has no upload/URL ingestion endpoint to validate MIME/hostnames, and the frontend renders arbitrary API error detail. |
| Outbox, replay, operator abuse | Blocked | Content is immutable, but no worker/claim/retry/dead-letter/replay authorization exists; outcome enqueue timing is wrong for quorum. This leaves availability/integrity controls unproven. |
| Audit/security events and observability | Blocked | Business audit/domain facts are created, but no reviewed F04 failure path inserts `certification_security_events`; denied authorization, replay, spoof, restricted download, and rate-limit events cannot meet PRD 14 §10/15. |
| Retention/legal hold | Design-only | Metadata fields exist on artifacts and policies, but no retention/legal-hold job, authorization, proof-of-deletion, or restricted raw-email lifecycle is implemented. |
| Secrets, configuration, dependency posture | Partial | No production token/provider secret was found in the reviewed F04 code. Development configuration still includes known local DB defaults, and there is no F04 SCA/SBOM/SAST/container scan gate or digest-pinned PostgreSQL image. |

## Local blockers versus controlled-environment gates

The following are **local implementation/security blockers**, not a reason to wait for a provider: source/project database constraints; confirmation-read/action authorization; SOD/service-identity rules; due-time enforcement; F04 rate limits and security-event recording; fake/recorded outbox and inbound interfaces; artifact scan/authorization contracts; database grants/RLS; restricted data redaction/retention hooks; safe problem details; concurrency/authorization/security tests; and a durable F05 contract event.

The following remain **external acceptance gates** and must stay `NOT_CONFIGURED`/`ACTION_REQUIRED` until approved: email provider and sender domain; dedicated monitored mailbox; callback-signing material and provider retention terms; controlled inbound webhook/subscription or polling authority; production recipient/delegate/quorum/SLA/retention policy; SSO/OTP/MFA/step-up design; F05 consumer deployment; and sandbox/live send, spoof, and reply exercises. No fixture, timeout, delivery receipt, or UI state may pass these gates.

## Validation performed

- `git diff --check` — passed.
- `npm audit --omit=dev --audit-level=high` — `0 vulnerabilities`.
- Focused secret-pattern scan — no production credential material found. It reported the intentional local-only `vms_local` database default in `application-local.yml`/`compose.yaml` and test fixtures; this remains a deployment hardening concern, not a leaked provider credential.
- `mvn -q -Dit.test='CertificationWorkflowIT,BusinessConfirmationIT,CertificationPersistenceIT,CertificationOpenApiIT,F04RegressionIT' failsafe:integration-test failsafe:verify` — **failed: 30 tests, 15 failures, 1 error**. The run reproduced missing cross-scope constraints, request scope mutation, SOD, due-time, project-action authorization, evidence validation, closure integrity, correlation documentation, and quorum behavior. These are local failures. No live external integration was invoked.

## Reconciliation with existing F04 reviews

Existing backend/test/frontend findings are retained rather than copied under competing identifiers. [SECURITY_ISSUES.md](SECURITY_ISSUES.md) maps each security finding to the prior review IDs. In particular, `F04-BE-012`'s historical statement that no F04 tests exist is now stale: F04 test classes exist and were run, but they fail and do not provide the necessary adversarial coverage. The substantive conclusion remains open.

The previously unrecorded issue is **F04-SEC-004**: project-scoped read authority is not propagated into the confirmation-request response. It is distinct from `F04-BE-004`, which concerns action authorization.
