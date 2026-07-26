# F03 test issues

| Priority | Missing or misleading evidence | Why it matters | Required test |
|---|---|---|---|
| P0 | Only `delivery_plan_baselines` is mutation-tested (`DeliveryLinearIT.java:111-114`). | It leaves mutable frozen plan/deliverable/link/recipient/criteria content unproven. | Direct SQL and API/webhook tests for all frozen and superseded version-owned tables; assert permitted live projection is separate/audited. |
| P0 | One `ANY_ONE` approval path only (`DeliveryLinearIT.java:68-109`). | Quorum, SOD and concurrency are release invariants. | Matrix for ANY_ONE/ALL/N_OF_M, duplicate/race votes, inactive/scoped/conflicted approvers, rejection and double-freeze/outbox. |
| P0 | One global fixture secret (`DeliveryLinearIT.java:42,351-355`). | Cannot prove per-connection/tenant secret isolation or rotation. | Two connections with distinct resolved secrets, old/new key rotation and cross-signature rejection. |
| P0 | No F03 Playwright spec; `e2e/demo.spec.ts` is unrelated. | UI tests do not prove real route/API contract, security state or browser flow. | Add labelled intercepted tests and at least one backend-backed F03 E2E; state which is which in CI output. |
| P1 | No ownership/adversarial link tests. | A browser-supplied connection/issue can cross provider workspace boundaries. | Wrong org/engagement connection, wrong workspace/team issue, forged URL/metadata, duplicate/multi-link-rationale/inaccessible cases. |
| P1 | No worker/job tests. | Current endpoint processing hides races and does not prove async durability. | Queue claim/lease/retry/dead-letter/replay/idempotency and callback returns after durable enqueue only. |
| P1 | No input-bound/rate-limit callback tests. | Public endpoint can consume resources before body check. | Empty, malformed, compressed, content-type, boundary-size, oversize, burst/rate-limit and timing tests. |
| P1 | No external adapter/mail failure tests. | External boundaries must fail explicitly, not appear connected. | Fake GraphQL HTTP-200 errors/pagination/rate-limit and fake email attempts/retry/dead-letter/readiness tests. |
| P2 | Frontend tests do not assert DTO response enums/nullability. | Existing frontend/backend contract drift can compile yet fail rendering. | Contract fixtures generated from OpenAPI/spring responses and tests for all state/status/null combinations. |
| P2 | Time is wall-clock based. | Replay-window tests can flake and cannot exhaust edge boundaries. | Inject fixed/mutable `Clock`; test 59/60/61-second and provider timestamp ordering boundaries. |

## Test gate

Do not report `E2E-03` complete until it drives the actual secured frontend/backend path with Testcontainers or a deployed isolated test environment. Intercepted Playwright tests remain valuable UI tests but must be reported separately. Live OAuth, tenant GraphQL, registered webhook, and real commitment email remain external acceptance gates, not skipped local test cases.

## Post-fix disposition — 2026-07-26 (pre-V9 re-review)

Focused rerun:

```text
mvn -B -f backend/pom.xml \
  -Dit.test=DeliveryLinearIT,DeliveryApprovalConcurrencyIT verify
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Passing tests do not close paths they do not exercise. This was the pre-V9
reviewer rerun. A subsequent V9 migration and focused transition/lineage test
now target the database evidence-integrity blocker; that item is **FIXED PENDING
FOCUSED V9 RE-REVIEW**. The implementation lead reports 46/46 for the resulting
full Maven lane. That interim label is superseded by the V9 focused re-review at
the end of this file.

| Original test issue | Disposition | Residual evidence required |
|---|---|---|
| P0 frozen-table mutation coverage | **FIXED PENDING FOCUSED V9 RE-REVIEW** | The pre-V9 suite missed stable lineage and transition bypass. The new V9-focused test targets code/plan/delete, invalid state, identity/time and frozen-lineage paths; verify assertions and migration behavior before closure. |
| P0 quorum/SOD/concurrency matrix | **PARTIAL** | `ANY_ONE`, owner conflict and concurrent `ALL` pass; add `N_OF_M`, stale/inactive/out-of-scope authority, rejection and full duplicate/race matrix. |
| P0 per-connection secret/rotation | **FIXED FOR LOCAL FIXTURE** | Two references, current/previous key and cross-signature rejection pass. Add prior-key retirement/revocation acceptance for production adapter. |
| P0 no F03 Playwright | **PARTIAL, accurately labelled** | Eight intercepted browser-contract cases now pass. No backend-backed/full-stack case exists; `E2E-03` remains incomplete. |
| P1 ownership/adversarial links | **SUBSTANTIALLY FIXED** | Forged metadata, wrong team and rationale are covered; add cross-org connection and inaccessible/broken retention cases. |
| P1 no worker/job tests | **OPEN** | Worker/job implementation is absent; add claim/lease/crash/retry/backoff/dead-letter/replay tests when implemented. |
| P1 no callback bound/rate tests | **PARTIAL** | Malformed, wrong content type and post-allocation oversize pass; add exact bounds, compressed/burst/rate/concurrency/latency cases. |
| P1 no adapter/mail failure tests | **OPEN LOCALLY / LIVE EXTERNAL** | Add fake GraphQL and fake-mail retry/readiness tests locally; keep tenant/provider tests external. |
| P2 frontend enum/nullability drift | **PARTIAL** | Hand-written runtime types and intercepted fixtures are aligned; validate actual Spring nullable responses against generated OpenAPI/frontend schemas. |
| P2 wall-clock timing | **OPEN** | Inject clocks and test 59/60/61-second replay plus older/equal/newer provider timestamp ordering. |

The Playwright fixture intercepts `**/api/v1/**`, so its 8/8 result is not Java,
PostgreSQL, JWT/BFF, live Linear or mail evidence. The E2E catalog labels that
correctly; keep that wording in every release/status artifact.

## V9 focused re-review — 2026-07-26

**P0 test disposition: PARTIALLY RESOLVED; LOCAL TEST GATE STILL BLOCKING.**

The new
`stableLineageAndExplicitVersionTransitionsAreDatabaseEnforced` test is valuable:
it covers stable code/plan/delete, draft bypass, checksum/version changes,
invalid pending/frozen states, identity/content/optimistic/timestamp mutation
and preservation of the prior frozen version. The implementation lead recorded
the full backend result as 46/46.

It does not cover three remaining P0 paths:

- after submit and before approval, perform the otherwise valid
  `PENDING_APPROVAL -> FROZEN` update and assert rejection plus zero baseline/
  outbox side effects;
- perform a valid service rejection, then attempt update/insert/delete on every
  version-owned child table and assert immutable signed evidence;
- insert/update a deliverable-version using a stable deliverable from a
  different plan and assert database rejection.

Until those assertions and guards pass, the overall database-integrity P0 is not
closed.

The worker/job, callback perimeter, fake-adapter/mail failure, full
quorum/authority, OpenAPI-runtime contract and deterministic-clock gaps remain
**local test work for the complete provider-neutral vertical**. Intercepted
Playwright remains browser-contract evidence only. Live OAuth/GraphQL/registered
webhook/real mail and controlled full-stack E2E remain external production
gates.

## V10 focused re-review — 2026-07-26

**P0 test disposition: RESOLVED. The focused evidence now covers all three prior
local P0 paths.**

- `directFreezeRequiresQuorumBaselineOutboxAndAttributableAudit` rejects the
  plain zero-evidence freeze and each incomplete evidence stage, then accepts
  the complete matching quorum/baseline/outbox/audit set.
- `rejectionRequiresSignedVoteAndMakesVersionOwnedEvidenceImmutable` rejects an
  unsigned terminal transition, accepts the service rejection, and rejects
  representative child update/delete/insert operations under the shared guard.
- `deliverableVersionCannotCrossPlanOnInsertOrMove` rejects cross-plan insert
  and ownership move and permits the legitimate same-plan clone. V10's migration
  preflight independently rejects inconsistent existing rows before installing
  the ongoing trigger.
- `concurrentAllQuorumFreezesExactlyOnce` remains green and proves two votes,
  one terminal state, one baseline and one outbox under the service row lock.

Recorded evidence:

```text
DeliveryLinearIT: 14
DeliveryApprovalConcurrencyIT: 1
Full Maven: 49 tests, 0 failures, 0 errors, 0 skipped
BUILD SUCCESS
```

The reviewer did not rerun Maven. A targeted injected failure after
baseline/outbox/audit creation would add explicit rollback evidence, but the
inspected transaction/order and existing concurrency path are sufficient to
close the P0 test gate.

All prior P1 test gaps remain **local** for the complete provider-neutral
vertical: worker/job failure behavior, callback perimeter, fake GraphQL/mail,
full quorum/authority matrix, generated OpenAPI/runtime contracts and
deterministic clocks. Intercepted Playwright is still browser-contract evidence.
Only live provider/mail and controlled full-stack acceptance remain external.
