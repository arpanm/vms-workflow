# F03 — Planning and Linear Test Cases

**Traceability:** PRD 02, 03, 07, 09, 13–16, 19, 20, 22.
**Test boundary:** all cases below are executable locally with Testcontainers PostgreSQL and fake/recorded provider adapters unless marked **external acceptance**. No test uses a live Linear workspace, production OAuth secret, webhook registration or real commitment-mail provider without tenant authorization.

## Plan, baseline and approval

- `T-PLAN-001` (formerly `T-PLAN-001/002`) — A plan with no deliverables, absent approved no-deliverables exception, missing mandatory field/criterion/owner/target date/dependency-or-none/risk-or-none/assignment, invalid allocation/date, inactive contact, unresolved invalid link, or absent required recipient preview cannot submit; response identifies each blocker and leaves state `DRAFT`.
- `T-PLAN-002` — A complete plan routes only to eligible scoped approvers, snapshots the recipient preview (ArrowFoundry, Reliance stakeholders, Central Procurement CC) and records submitter/configuration/audit facts.
- `T-PLAN-003` (formerly `T-PLAN-003–005`) — Data-driven `ANY_ONE`, `ALL`, and `N_OF_M` quorum progresses only on the required eligible approvals; duplicate, stale, out-of-scope and disabled-user votes do not count.
- `T-PLAN-004` — Default separation of duties rejects creator self-approval, vendor self-certification and platform-admin implicit business approval; an explicitly configured self-approval policy is recorded with authority snapshot.
- `T-PLAN-005` — Quorum signs the exact canonical plan checksum, atomically freezes the version, creates baseline/plan-time snapshot records (including explicit fetch failure), queues commitment email and updates eligible month state; concurrent approval requests cannot double-freeze/queue.
- `T-PLAN-006` — Frozen/approved versions reject in-place mutation; revision clones stable deliverable lineage, requires reason/impact, displays additions/removals/field diff, needs new quorum and preserves original versus effective baseline metrics.
- `T-PLAN-007` — Dependency cycles are rejected; internal/external/Linear dependencies retain owner, target resolution date and blocking classification; a permitted cross-project dependency remains visible.
- `T-PLAN-008` — Effective-dated employee assignment requires active allocation or an authorized warning/exception, is snapshotted, and a Linear assignee suggestion never silently creates an assignment.
- `T-PLAN-009` — An active shared-core delegation can act only for a configured authority holder and exact `delivery.plan.approve` scope; the immutable approval records authority holder, acting subject, delegation ID/expiry and separation-of-duties outcome. Expired, revoked, cross-engagement and project-mismatched delegations are hidden as not found.

## Commitment communication

- `T-MSG-001` (formerly `T-MSG-001/002`) — Commitment rendering uses only the approved/frozen version and checksum, includes delivery/owner/target/acceptance/Linear-link summary and accessible HTML plus plain text; later revisions do not alter the archived message.
- `T-MSG-002` — Missing Procurement CC, ArrowFoundry recipient or Reliance product stakeholder blocks enqueue/send; recipient/group snapshot, message ID, correlation ID, attempts and immutable archive reference are retained.
- `T-MSG-003` — Fake-adapter send failure takes bounded retry then dead-letter with health visibility; replay after correction sends at most once per idempotency key and preserves prior attempts.
- `T-MSG-004` — Sent, delivered, read, bounced, silence and provider callback never approve/freeze/certify/confirm. Resend/correction retains original message lineage.
- `T-MSG-005` — **External acceptance:** configured tenant mail provider/sender/mailbox sends only after approved credentials/contact configuration; validate sandbox/live provider metadata and retention without exposing restricted message content or secrets.

## Linear connection, links and projections

- `T-LIN-001` — Supported Linear URL and `TEAM-123` identifier resolve through the adapter to immutable UUID, identifier and URL; non-Linear/malformed input, inaccessible workspace/team and duplicate `(deliverable_version, UUID)` link are rejected without partial records.
- `T-LIN-002` (formerly `T-LIN-002/003`) — OAuth authorization validates one-time state and PKCE verifier; callback rejects missing/mismatched/expired/replayed state. Browser bundles/storage, ordinary database columns, API responses, logs and Swagger examples contain no access/refresh token, verifier, client secret or webhook secret.
- `T-LIN-003` — Adapter handles GraphQL `errors` on HTTP 200, response-rate-limit headers, server-side pagination and allowed fields; it does not request write/admin scope, expose unauthorized description/assets, or hard-code a numeric provider limit.
- `T-LIN-004` (formerly `T-LIN-004/005`) — Webhook raw bytes with invalid/missing `Linear-Signature` HMAC-SHA256, invalid/missing `Linear-Timestamp`/`webhookTimestamp`, timestamp outside the approximately one-minute replay window, mismatched workspace/connection or malformed `Linear-Delivery` UUID is rejected, security-audited/quarantined as appropriate, and causes no state mutation.
- `T-LIN-005` — A valid signed raw-body delivery is durably persisted/deduplicated and enqueued before HTTP 200; response completes inside five seconds and worker processing is asynchronous.
- `T-LIN-006` — Duplicate `Linear-Delivery` UUID or event fingerprint, concurrent duplicate submission, worker retry and admin replay update current projection/history at most once and do not duplicate notifications.
- `T-LIN-007` — Plan-time and month-end snapshots retain source payload hash/fetched time/failure state; later current issue change/delete/inaccessibility alters only live projection and displays a live-vs-snapshot diff.
- `T-LIN-008` (formerly `T-LIN-008/009`) — Missed webhooks are repaired by bounded `updatedAt` delta and nightly linked-issue reconciliation using checkpoint/pagination; no page load triggers external polling.
- `T-LIN-009` — Expired/revoked connection becomes `ACTION_REQUIRED`, preserves last-known UUID/history with stale badge, blocks new link resolution as configured, resubscribes/tests after reconnect and exposes reconciliation lag/last verified delivery/error/dead-letter health.
- `T-LIN-010` — `Done`/`COMPLETED` updates normalized execution progress only. It creates no deliverable acceptance, criterion decision, certification, confirmation, invoice eligibility or month business transition; conversely, authorized acceptance/rejection is possible with linked issues respectively open/done when rationale is recorded.
- `T-LIN-011` — Custom state preserves provider ID/name/type/category and is mapped only through versioned configured rules, yielding `UNKNOWN` rather than name guessing when unmapped.
- `T-LIN-012` — Historical import stores retrieval source/checksum and labels current API-only data `CURRENT_STATE_ONLY`; it does not label it a historical month-end snapshot absent source event history/export.
- `T-LIN-013` — **External acceptance:** approved Linear OAuth app/app actor completes OAuth/PKCE, least-privilege scope, authorized GraphQL resolution and registered signed webhook against the tenant workspace; revoke/reconnect and webhook test are documented.
- `T-LIN-014` — A scheduled/provider-neutral delta run reads only a configured maximum page size, advances the compound cursor after a clean page, stores immutable per-attempt counts/checksum, resumes after interruption, and exposes only scoped checkpoint/job summaries. A partial GraphQL response records its errors and does not advance the cursor.

## Security, persistence and API

- `T-F03-SEC-001` — Testcontainers PostgreSQL from empty database applies all Flyway migrations and constraints; duplicate month/link/delivery, invalid lifecycle transition, cross-tenant foreign reference and destructive evidence mutation fail atomically.
- `T-F03-SEC-002` — MockMvc/WebTestClient proves unauthenticated requests are denied; authenticated but wrong organization/engagement/project/object assignment and unauthorized integration replay are denied without record disclosure; valid scoped JWT succeeds.
- `T-F03-SEC-003` — Spring method/service authorization and PostgreSQL least-privilege role tests prove UI state cannot confer authority and reporting/API queries do not bypass tenant scope.
- `T-F03-SEC-004` — HTML/Markdown/Linear text and email rendering are output encoded; invalid input/oversized webhook is bounded/rate-limited; audit/security events redact tokens, secrets and restricted contact data.
- `T-F03-API-001` — Executable springdoc OpenAPI documents all F03 success/typed-error contracts without secret examples; protected `/v3/api-docs` and Swagger UI deny unauthenticated/unauthorized users and allow the configured documentation audience.

## Frontend and end-to-end

- `T-F03-UI-001` — Playwright verifies completeness blockers, frozen non-editability, approval checksum/email preview, revision diff, multi-link rationale, stale/broken-link error, current/snapshot/imported/superseded labels and permission-gated health/replay controls.
- `T-F03-UI-002` — Playwright verifies keyboard navigation, accessible names/error summaries and no secret/token rendering in plan, Linear or health screens.
- `E2E-03` — Product owner creates a complete plan, links fixture issues, receives approval quorum/freeze/baseline/outbox record, processes signed fixture webhook updates, views immutable current-versus-snapshot evidence, and reaches separate authorized delivery certification. Assert `Done` alone does not certify and a revision preserves original baseline lineage.
- `T-F03-FAIL-001` — Failure injection for GraphQL partial error/rate limit/timeout, OAuth revoke, invalid/duplicate/replayed webhook, queue crash, snapshot fetch failure, reconciliation interruption and mail send failure retains durable evidence, bounded retry/dead-letter/replay status and explicit stale/action-required UI without duplicate business action.
- `T-F03-OPS-001` — Review/operator runbook test validates health metrics, correlation IDs, replay authorization, signature-failure alert, stale-sync alert and safe remediation instructions; blocked live provider configuration is shown as readiness state rather than a false healthy connection.

## Completion evidence

Run JUnit 5, Spring Boot/Testcontainers integration and security suites, Playwright, Flyway validation and API documentation checks in CI; fix accepted code/security/accessibility findings and retain results. Production sign-off additionally requires `T-MSG-005` and `T-LIN-013` tenant-authorized external acceptance, which are not substitutes for local automated gates.

**Recorded local evidence (2026-07-26):** 49 backend Testcontainers tests, 47
frontend unit/contract tests and 26 intercepted Playwright cases (8
F03/cross-feature) pass. These results cover a narrow demonstrator; they do
not convert the remaining P1 catalog cases or external acceptance cases into
passing tests.
