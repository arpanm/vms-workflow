# F03 post-fix review

**Review date:** 2026-07-26
**Review cutoff:** V7/V8 implementation, before V9 lineage/transition remediation
**Decision at cutoff:** **BLOCKING — do not release the provider-neutral local vertical yet.**
**Current P0 disposition:** **RESOLVED BY V10.**
**Current scoped-vertical disposition:** **local P1 blockers remain open.**

The hardening work materially improves F03, and the focused Spring/Testcontainers
suite passes. It does not, however, close the frozen-evidence database invariant.
Live Linear OAuth/GraphQL/webhook registration and real commitment-mail delivery
remain separate external gates and are not treated as local failures or local
passes.

After this review was written,
`V9__delivery_lineage_and_transition_integrity.sql` and an explicit
lineage/transition integration test were added. The implementation lead reports
the full Maven lane at 46/46. Those changes directly target the P0 below, but
they were not part of this review cutoff; therefore the finding is retained as
historical evidence and marked **fixed pending focused re-review**, not silently
closed. The dated V9 focused re-review at the end now supersedes that interim
label: it verifies the original mutation shapes but identifies three remaining
P0 database paths. The subsequent V10 focused re-review closes those three P0
paths. The other P1/P2 dispositions are unaffected until separately remediated
and reviewed.

## Actionable findings

### P0 — BLOCKING at V7/V8 cutoff; fixed pending V9 re-review: frozen commitment identity is mutable and lifecycle transitions are bypassable

V8 protects version-owned rows in `delivery_plan_approvers`,
`delivery_recipient_snapshots`, `delivery_deliverable_versions`, criteria,
dependencies, assignments and links
(`V8__delivery_release_hardening.sql:197-274`). It does **not** protect
`delivery_deliverables`, whose `deliverable_code` and `plan_id` define stable
commitment identity (`V7__delivery_planning_linear.sql:61-67`). A direct update
of either field can therefore rewrite the meaning/lineage of a submitted or
frozen version without changing the signed checksum or baseline row.

The plan-version trigger is also not a complete transition guard:

- it checks authority only when the new state is `PENDING_APPROVAL`;
- its immutability branch is entered only when the **old** state is
  `PENDING_APPROVAL`, `FROZEN` or `SUPERSEDED`;
- consequently, direct `DRAFT -> FROZEN`, `DRAFT -> SUPERSEDED`,
  `DRAFT -> REJECTED` and other unsupported transitions are accepted;
- the allowed `PENDING_APPROVAL -> FROZEN|REJECTED` branch compares content
  fields but omits `plan_id`, `version`, `optimistic_version` and `frozen_at`
  (`V8__delivery_release_hardening.sql:167-185`);
- `REJECTED` content is not protected, although it is signed review evidence and
  the service supplies no valid rework path from that state.

The mutation test covers `delivery_deliverable_versions` and selected child
tables, but never `delivery_deliverables` or invalid direct state transitions
(`DeliveryLinearIT.java:89-128`). Green tests therefore do not establish the
required immutable-baseline invariant.

**Required fix:** add an append-only Flyway migration that guards stable
deliverable identity whenever any referenced version is protected; implement an
explicit database transition matrix; compare every non-transition column on the
allowed state changes; define and protect rejected/changes-requested evidence;
and add direct SQL tests for insert/update/delete/move operations on every
stable and version-owned table.

### P1 — local webhook processing is still an operator request, not a durable worker

`POST /deliveries/{deliveryId}/process` locks one requested queue row and runs
the provider payload synchronously (`LinearIntegrationService.java:359-498`).
There is no autonomous claimant, `FOR UPDATE SKIP LOCKED`, lease recovery,
bounded retry/backoff, dead-letter transition or replay-enqueue operation. V8
adds claim columns and an index but no consumer.

An exception after the queue is marked `PROCESSING` rolls the transaction back,
so malformed supported-event data returns to `QUEUED` without a retained
attempt/error and can fail forever. The current endpoint is useful as a local
diagnostic command but does not satisfy the webhook exit gate.

**Required fix:** implement and test a bounded worker and make the operator API
enqueue/replay work rather than execute untrusted payloads in the request.

### P1 — public callback resource controls remain post-allocation and incomplete

The controller binds `@RequestBody byte[]` before the service checks the
262,144-byte limit (`LinearIntegrationController.java:74-93`;
`LinearIntegrationService.java:269-279`). Strict JSON `consumes` is now present,
but there is still no connector/proxy limit, rate/concurrency control,
compression policy or constant-shape public error policy. The oversized-body
test proves only the post-allocation `400`.

**Required fix:** enforce request and decompression limits before materializing
the body, add rate/concurrency controls, and cover exact boundary, compressed
and burst cases.

### P1 — completeness exceptions and revision workflow are not authoritative or complete

Completeness now checks active links, recipient presence, allocation overlap,
target month, internal dependency ownership and active subjects. But any
20-character `link_exception_reason` is accepted without an exception record,
permission, approver or authority snapshot
(`DeliveryPlanningService.java:495-655`). There is no approved no-deliverables
exception. Non-empty recipient arrays are accepted without resolving controlled
contact groups.

Revision preserves the prior frozen state and baseline lineage, which is an
important fix, but it only clones identical content and stores reason/impact
(`DeliveryPlanningService.java:242-278`). There is no plan/deliverable update
API, field/add/remove diff, effective-baseline comparison or rework path after
rejection. The implemented state path still skips documented
`READY_FOR_REVIEW` and `APPROVED`, and the month commonly remains `ACTIVE`
rather than following the guarded plan transition.

**Required fix:** model approved exceptions with authority/audit facts; add the
documented editable-revision and diff path; and either implement the documented
state machine or explicitly revise the requirements/contracts before release.

### P1 — checksum v2 is broader but still omits revision commitment context

The checksum now uses length-prefixing and stable ordering and includes the
originally missing dependencies, approver authority, recipients, links and
plan-time snapshots (`DeliveryPlanningService.java:1042-1181`). It still omits
`prior_version_id`, `revision_reason`, `revision_impact`, plan identity and
engagement-month identity. Thus two revision commitments with different
reason/impact can sign the same content digest, even though the revision
communication and approval are required to cover those facts.

**Required fix:** define a versioned canonical document schema, include all
reviewed revision/identity facts, and add same-input/same-hash plus
one-field-change/changed-hash tests.

### P1 — the commitment path stops at an immutable pending outbox row

Freeze creates useful immutable rendered content and one idempotent outbox row.
There is no fake or production sender, attempt/retry/dead-letter worker, replay
or callback processing. Moreover, V7 makes the whole `commitment_outbox` row
immutable while the same row owns mutable `PENDING/SENT/RETRY/DEAD_LETTER`
status (`V7__delivery_planning_linear.sql:157-185,418-420`). The API reads that
status, so no future sender can advance it without defeating the trigger.

**Required fix:** keep payload/recipient/archive fields immutable and put
delivery state in a separately transition-guarded record (or narrowly permit
valid status transitions), then implement the local fake-adapter failure path.
Real provider/sender/mailbox acceptance remains externally blocked.

### P1 — duplicate fingerprint and failure/replay behavior are only partially idempotent

Reuse of one `Linear-Delivery` UUID with different connection/content now fails
closed, and exact UUID/body replay deduplicates. However, a different delivery
UUID with the same `(connection, raw-body)` fingerprint reaches the unique
constraint after a pre-check by delivery ID and becomes a generic `409`, not a
verified idempotent duplicate. Concurrent first receipt of the same UUID also
races between the select and insert. Neither case corrupts state, but neither
implements the documented duplicate/replay contract or records a sanitized
collision disposition.

**Required fix:** use a single atomic insert/conflict-return path that verifies
both delivery ID and fingerprint ownership/content, and test concurrent UUID
duplicates plus same-fingerprint/different-UUID delivery.

### P2 — runtime/frontend nullability is aligned, but OpenAPI is not executable contract evidence

Frontend dependency, snapshot, commitment and provider nullable types now match
runtime DTO values, and the intercepted fixture was synchronized. Most nullable
Java response fields still lack explicit OpenAPI nullability, including
provider state fields, timestamps, snapshot fields and no-connection health
identity (`LinearDtos.java:23-88`). Most response state strings also lack closed
`allowableValues`. Tests scan a few OpenAPI property names but do not validate
the response schema against actual nullable responses.

**Required fix:** annotate or model nullable/enumerated response schemas,
validate live Spring responses against generated OpenAPI, and generate or
runtime-validate frontend fixtures from that schema.

### P2 — deterministic time and database least privilege remain open

Both services still instantiate `Clock.systemUTC()` directly. Replay-window and
provider-ordering boundary tests are wall-clock based and do not cover
59/60/61-second edges or equal provider timestamps. F03 also has no
least-privilege PostgreSQL application/reporting role test.

## Verified fixes to retain

- Approval serializes on the plan/current-version row; the concurrent `ALL`
  test freezes once with one baseline and one outbox.
- Submission resolves active scoped role assignments, snapshots authority and
  rejects creator/coordinator/product-owner/vendor-owner conflicts.
- `WebhookSecretResolver` resolves current/previous keys by a connection-owned
  reference; cross-connection signing fails in the local fixture.
- Webhook HMAC verification uses exact raw bytes and constant-time comparison
  before JSON parsing; header/body replay timestamps, organization, optional
  connection and delivery UUID are checked before persistence.
- Server-recorded issue metadata replaces browser-asserted title/URL/state and
  is checked against connection organization/team; a second link needs rationale.
- Older provider `updatedAt` events are retained as `STALE_IGNORED`, audited and
  do not regress current state/projection.
- Live execution projection is separate and audited; `COMPLETED` does not mutate
  acceptance, certification, confirmation, invoice eligibility or plan/month
  approval state.
- Frontend literals and nullable types match current runtime DTOs, and all F03
  Playwright tests are explicitly intercepted browser-contract tests.
- No F03 database/API/frontend field introduces salary, payroll, CTC, employee
  rate, markup or cost data.

## Original code-issue disposition

This table records the initial pre-V9 disposition. The dated V9 focused
re-review below supersedes its “pending” label for the P0 integrity item.

| Original issue | Disposition | Post-fix evidence / remaining action |
|---|---|---|
| P0 frozen/superseded content mutable | **FIXED PENDING FOCUSED V9 RE-REVIEW** | At the V7/V8 cutoff, stable `delivery_deliverables` and transition-column holes remained. V9 and a new transition/lineage test now target those exact paths; closure is not asserted until focused review. |
| P0 SOD/authority caller controlled | **SUBSTANTIALLY FIXED** | Server resolves scoped assignments, snapshots authority and DB rejects unsnapshotted/conflicted votes. `N_OF_M`, inactive/stale and rejection matrices remain unproven. |
| P0 one global webhook secret | **FIXED FOR LOCAL VERTICAL** | Per-reference resolver and two-connection/previous-key tests pass. Secret-manager TTL/key retirement/revocation is an external production gate. |
| P1 link connection/workspace ownership and client metadata | **FIXED FOR RECORDED ADAPTER** | Both resources are authorized; server metadata and org/team are checked; multi-link rationale is enforced. Live GraphQL ownership remains external. |
| P1 incomplete checksum | **PARTIAL** | Original missing collections are covered; revision and plan/month identity remain omitted. |
| P1 weak completeness | **PARTIAL** | Several blockers added; exception authority, controlled contact groups and no-plan workflow remain absent. |
| P1 request-driven queue processing | **OPEN** | Still a synchronous permissioned process endpoint; no durable worker/retry/dead-letter. |
| P1 public callback perimeter | **PARTIAL** | JSON content type and application size check added; allocation, compression, rate/concurrency and response-shape controls remain. |
| P1 revision mutates old baseline/no diff | **PARTIAL** | Old version stays `FROZEN` and baseline lineage is retained; no editable revision diff/effective comparison exists. |
| P2 database lifecycle/dependency/queue constraints | **PARTIAL; transition/lineage portion pending V9 re-review** | V9 now targets the explicit transition matrix and stable deliverable guard. Dependency SQL ownership and queue transitions remain open. |
| P2 stale provider event regression | **FIXED** | Timestamp comparison, `STALE_IGNORED` disposition and audit test are present; equal-timestamp semantics remain a boundary follow-up. |
| P2 weak DTO bounds/semantics | **SUBSTANTIALLY FIXED** | Size/email/pattern/list constraints added. Explicit response nullability/enums and generated contract validation remain. |

## Original test-issue disposition

This table records the initial pre-V9 disposition. The dated V9 focused
re-review below supersedes its “pending” label for the P0 integrity test item.

| Original test issue | Disposition | Current evidence / remaining test |
|---|---|---|
| P0 frozen-table mutation coverage | **FIXED PENDING FOCUSED V9 RE-REVIEW** | At cutoff, stable deliverable identity, transition bypass, deletes/moves and rejected evidence were untested. A new V9-focused integration test now targets them; evidence awaits review. |
| P0 quorum/SOD/concurrency matrix | **PARTIAL** | `ANY_ONE`, owner conflict and concurrent `ALL` are covered; `N_OF_M`, inactive/out-of-scope/stale authority, rejection and complete race matrix are not. |
| P0 per-connection secret/rotation evidence | **FIXED FOR LOCAL FIXTURE** | Current/previous key, cross-connection rejection, raw bytes and changed-content delivery reuse are tested. Key retirement remains untested/external. |
| P0 no F03 Playwright | **PARTIAL, correctly labelled** | Eight intercepted browser-contract cases exist and pass. Backend-backed/full-stack cases remain blocked and are not represented as passing. |
| P1 ownership/adversarial link tests | **SUBSTANTIALLY FIXED** | Forged metadata, wrong team and multi-link rationale are tested; cross-org connection and inaccessible/broken retention matrix remains. |
| P1 no worker/job tests | **OPEN** | Worker/job implementation is absent. |
| P1 callback bound/rate tests | **PARTIAL** | Malformed, content type and post-allocation oversize paths exist; exact limits, compressed/burst/rate/timing paths do not. |
| P1 adapter/mail failure tests | **OPEN LOCALLY / LIVE EXTERNAL** | No fake GraphQL/mail adapter failure lane; live tenant/provider tests remain external. |
| P2 frontend enum/null contract tests | **PARTIAL** | Hand-written null/enum tests and intercepted fixtures exist; generated OpenAPI/runtime validation does not. |
| P2 deterministic clock boundaries | **OPEN** | Services still own system clocks; boundary matrix is absent. |

## Evidence and claim audit

The command below was rerun against the reviewed worktree:

```text
mvn -B -f backend/pom.xml \
  -Dit.test=DeliveryLinearIT,DeliveryApprovalConcurrencyIT verify
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

That 11-test result is the pre-V9 reviewer rerun. The implementation lead
subsequently reported 46/46 for the full Maven lane after adding V9; this report
does not substitute that result for a focused inspection of the new migration
and assertions.

The existing 8 F03/cross-feature Playwright cases use
`page.route("**/api/v1/**")` in `e2e/fixtures/delivery-api.ts`; they are
browser-contract evidence only. `E2E-F03-101` through `103` remain blocked in
the E2E catalog. `E2E-03` in `TEST_CASES.md` must not be reported complete
because it also requires the backend path and separate certification capability.

Documentation needs correction with the code fix:

- `FIXES-BACKEND.md` says only the explicit pending transition is allowed and
  that protected commitment content is immutable; the trigger holes above make
  those claims too broad.
- `API.md` says frozen content changes only through revision; stable deliverable
  identity currently disproves that at the database boundary.
- `TEST_AUTOMATION.md` calls request-triggered processing “worker processing”;
  it should say operator-triggered processing.
- `FEATURE_STATUS.md` and `E2E_REGRESSION_CASES.md` still refer to a recorded
  five-case F03 lane although the current targeted suite has 10
  `DeliveryLinearIT` tests plus one concurrency test.
- `CODEGEN-FRONTEND.md` retains a stale note that the synchronized Playwright
  suite was not run, while the later status/E2E ledgers correctly record 8/8.

## External gates, unchanged

Do not claim or enable real Linear connectivity until the tenant approves the
OAuth app/app actor, scopes, workspace/team, GraphQL access, webhook
registration and per-connection secret-manager values. OAuth/PKCE and live
GraphQL/revocation/reconciliation are not implemented here.

Do not claim commitment delivery until the mail provider, sender, controlled
mailbox, recipient groups and retention are approved and the live acceptance
test passes. These external gates do not excuse the open local worker, fake-mail
failure and database-integrity findings above.

## V9 focused re-review — 2026-07-26

**Disposition:** the exact V7/V8 mutation holes are fixed, but the **overall P0
local integrity blocker is not resolved**. The provider-neutral vertical remains
**BLOCKING** pending the three database paths below.

### What V9 closes

Direct inspection confirms that V9:

- adds an update/delete trigger for stable `delivery_deliverables` identity and
  plan lineage (`V9__delivery_lineage_and_transition_integrity.sql:3-32`);
- replaces the V8 plan-version function with explicit `DRAFT -> DRAFT`,
  `DRAFT -> PENDING_APPROVAL`, and
  `PENDING_APPROVAL -> FROZEN|REJECTED` branches, rejecting other version-state
  updates (`V9__delivery_lineage_and_transition_integrity.sql:34-148`);
- compares plan/version identity and all listed commitment/lineage fields,
  constrains checksum shape, requires exact optimistic-version increments, and
  validates submitted/frozen timestamp shape;
- leaves the original version `FROZEN` when revision creates a new draft.

The new
`stableLineageAndExplicitVersionTransitionsAreDatabaseEnforced` integration test
meaningfully covers stable code/plan update and deletion, draft bypasses,
checksum/version mutation, invalid pending/frozen transitions, content/identity/
optimistic/timestamp changes and frozen revision lineage
(`DeliveryLinearIT.java:566-703`). The implementation lead recorded the full
backend lane as 46 tests passing. That result is accepted as run evidence; this
focused review did not rerun it.

### P0 residual 1 — a pending version can still be frozen without quorum or atomic evidence

The V9 `PENDING_APPROVAL -> FROZEN` branch checks column shape but never queries
`delivery_plan_approvals`, calculates quorum, or requires a baseline,
plan-snapshot/outbox and audit evidence (`V9...sql:121-143`). Therefore this
direct SQL shape is permitted immediately after submit, with zero approvals:

```sql
UPDATE delivery_plan_versions
SET state = 'FROZEN',
    frozen_at = CURRENT_TIMESTAMP,
    optimistic_version = optimistic_version + 1
WHERE id = :pending_version_id;
```

The new test does not try that case. Its pre-approval freeze attempts always
also tamper with title/plan/version, use the wrong optimistic increment, or use
an invalid timestamp (`DeliveryLinearIT.java:617-649`); the only valid-shape
freeze is performed later through the service after approval
(`DeliveryLinearIT.java:651-656`).

This bypass violates the core rule that eligible quorum atomically creates the
frozen baseline, snapshots, outbox and audit evidence. The trigger can verify
these rows because `DeliveryPlanningService.freeze` inserts baseline/outbox
before updating the version state in the same transaction.

### P0 residual 2 — rejected signed child content remains mutable

V9 makes the `delivery_plan_versions` row terminal after `REJECTED`, but the V8
`protected_delivery_version_state()` function still protects only
`PENDING_APPROVAL`, `FROZEN` and `SUPERSEDED`
(`V8__delivery_release_hardening.sql:197-207`). The child-table guards use that
function. After a rejection, approvers, recipients, deliverable-version content,
criteria, dependencies, assignments and links can therefore be changed or
deleted while the immutable rejection approval still points to the old signed
checksum. V9 neither replaces that function nor tests a rejected child mutation.

### P0 residual 3 — stable deliverable and version plan ownership is not constrained on insert

`delivery_deliverables.plan_id` and `delivery_plan_versions.plan_id` are not
cross-checked when `delivery_deliverable_versions` is inserted. The existing
project trigger checks project engagement, and the V9 stable-lineage trigger
checks only later update/delete of the stable row. A draft can therefore receive
a deliverable-version whose stable deliverable belongs to another plan; V9 then
makes that incorrect association difficult to repair. The focused test does not
attempt a cross-plan association.

**Required closure:** extend the append-only migration to (1) require approval
quorum and atomic freeze evidence before `FROZEN`, (2) treat `REJECTED` as
protected in every version-owned child guard, and (3) enforce stable/version
plan equality on insert/update. Add one direct SQL assertion for each path, plus
a valid `REJECTED` transition and immutable-rejection-content assertion.

### Scope of the remaining non-P0 findings

The documented P1 residuals are **local**, not external:

- autonomous webhook claim/retry/dead-letter/replay;
- pre-allocation callback size/decompression/rate/concurrency controls;
- authority-backed link/no-plan exceptions and controlled recipient groups;
- editable revision plus field/add/remove diff and complete state/rework path;
- revision/plan/month facts in the canonical checksum;
- fake commitment-mail adapter, attempts and valid outbox-status transitions;
- atomic concurrent delivery/fingerprint dedupe.

Those items block claiming the **complete scoped provider-neutral F03 vertical**,
even after the P0 database fixes. They do not prevent describing the current
implementation as a narrower local demonstrator, provided that limitation is
explicit.

The genuinely external gates remain live Linear OAuth/PKCE/app actor, tenant
GraphQL/workspace/team authorization, webhook registration/secret-manager
operation, real mail provider/sender/mailbox/contact approval, controlled
full-stack identity environment and tenant-authorized acceptance. Those external
gates additionally block **full F03 production completion**.

## V10 focused re-review — 2026-07-26

**Disposition:** **RESOLVED — all identified local P0 integrity blockers for the
scoped provider-neutral vertical are closed.** The documented local P1/P2 gaps
remain open and still prevent claiming the complete scoped vertical or full F03.

### P0 path 1 — zero-evidence direct freeze: resolved

V10 replaces the terminal branch of `delivery_version_content_guard` so a
shape-correct `PENDING_APPROVAL -> FROZEN` transition now requires:

- the configured eligible, checksum-matching approval quorum;
- no recorded reject vote;
- a checksum-matching baseline with the current deliverable count;
- the matching version/baseline commitment outbox row and idempotency key; and
- a `PLAN_FROZEN` audit actor attributable to a matching approve vote
  (`V10__delivery_terminal_evidence_integrity.sql:108-213`).

`DeliveryPlanningService.freeze` now writes baseline, outbox and attributable
audit before the final state update (`DeliveryPlanningService.java:281-326`).
The outer approval method remains `@Transactional` and locks the plan/current
version with `FOR UPDATE`, so a trigger or later statement failure propagates
and rolls back the approval/evidence/state unit rather than leaving a partial
freeze.

`directFreezeRequiresQuorumBaselineOutboxAndAttributableAudit` tests the plain
zero-approval freeze, then independently missing baseline, outbox and audit,
before proving that the complete matching evidence permits the transition
(`DeliveryLinearIT.java:705-797`).

### P0 path 2 — rejected signed child evidence: resolved

V10 adds `REJECTED` to `protected_delivery_version_state`, which is used by the
shared insert/update/delete guards for approvers, recipients, deliverable
versions, criteria, dependencies, assignments and links (`V10...sql:4-17`;
`V8__delivery_release_hardening.sql:209-274`). The terminal transition also
requires an attributable `REJECT` vote signed over the current checksum
(`V10...sql:130-142`).

The rejection test proves that a direct reject without a signed vote fails, the
legitimate service rejection succeeds, and representative rejected child
update/delete/insert operations fail (`DeliveryLinearIT.java:799-844`). Because
the remaining child tables share the same guard function, the structural fix
applies to the entire listed set.

### P0 path 3 — cross-plan deliverable/version lineage: resolved

V10 first scans existing deliverable-version rows and aborts migration if stable
deliverable ownership differs from plan-version ownership. It then installs a
trigger for every insert or ownership update requiring
`delivery_deliverables.plan_id = delivery_plan_versions.plan_id`
(`V10...sql:221-262`).

The focused test rejects a cross-plan insert and a later ownership move while
allowing a legitimate same-plan clone (`DeliveryLinearIT.java:846-929`). The
preflight runs before the trigger is installed in the same Flyway migration, so
an inconsistent pre-existing database cannot silently adopt the constraint.

### Concurrency, atomicity and recorded evidence

The existing concurrent `ALL` test still drives two approvers against the locked
current version and proves exactly two approvals, one frozen version, one
baseline and one outbox (`DeliveryApprovalConcurrencyIT.java:46-144`). V10's
attributable-audit requirement is compatible with the final approver because
that approval is inserted before `freeze` runs.

The implementation lead recorded:

```text
DeliveryLinearIT: 14
DeliveryApprovalConcurrencyIT: 1
Full Maven: 49 tests, 0 failures, 0 errors, 0 skipped
BUILD SUCCESS
```

This focused review accepted that recorded run without repeating it. There is no
dedicated injected failure after baseline/outbox/audit creation; adding one
would strengthen rollback evidence under the existing local failure-testing
P1, but the transaction boundary, uncaught database failure and ordering are
sufficient to close the identified P0 atomicity concern.

### Remaining scope

No local P0 from the reviewed F03 set remains. The following are still **local
P1 blockers for claiming the complete scoped provider-neutral vertical**:
autonomous webhook worker/retry/dead-letter/replay, public callback perimeter
controls, authority-backed exceptions/contact resolution, editable revision and
diff/rework flow, complete revision checksum context, fake-mail attempts and
outbox transitions, and atomic fingerprint/delivery dedupe.

Live Linear OAuth/GraphQL/webhook registration, production secret-manager
operation, real commitment-mail delivery and controlled full-stack acceptance
remain **external gates for full production F03**. V10 does not change or
reclassify either group.
