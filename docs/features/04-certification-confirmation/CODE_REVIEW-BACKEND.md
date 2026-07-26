# F04 Backend Code Review

**Review date:** 2026-07-26
**Scope:** V11 migration; certification/confirmation controllers, DTOs, services, authorization, provider/configuration changes, exception behavior, and F01–F03 regression surface.
**Reference:** F04 `TASKS.md` and `TEST_CASES.md`; PRD 08, 09, 13–16 and 22.

## Outcome

No P0 was established. The backend is **not ready for the F04 local release gates (G1–G3)**: 12 P1 findings are open. The strongest blockers are database scope/immutability gaps, missing separation-of-duties checks, confirmation expiry/quorum notification defects, an incomplete local outbox/job/inbound/closure vertical, and no F04 automated tests.

The provider-neutral boundary is directionally correct: configuration defaults to `NOT_CONFIGURED`, the email adapter does not send live email, tokens are high entropy and PBKDF2-hashed, and transport status is separate from confirmation state. Those safeguards do not compensate for the local workflow gaps recorded below.

## Validation performed

- Read V11 and all F04 Java sources under `api`, `application`, and `security`, together with configuration and exception changes.
- Traced F01–F03 data/authorization dependencies: active membership/role assignments, frozen F03 plan/baseline/recipient/Linear data, and F02 attendance snapshots.
- Inspected the state, idempotency, hash, outbox, token, quorum, readiness, reopen, and audit paths plus their database triggers.
- Ran `mvn clean verify` in `backend/`: **PASS**. It compiled 59 production sources, migrated an empty PostgreSQL Testcontainers database through V11, and passed 49 existing tests. The suite contains five test classes, all for F01–F03/JWT; it contains no F04 tests.

## Strengths retained

- V11 is additive and applies cleanly from an empty database.
- Several important records have immutable triggers, partial-current indexes, foreign keys, and checks.
- Service mutations generally require `If-Match` and idempotency keys, lock the month or request, and write audit/domain/outbox records in one transaction.
- The secure-token implementation uses 256 bits of randomness, per-token salt, PBKDF2-HMAC-SHA256, constant-time comparison, and a consumed marker.
- Confirmation actions are authenticated and use the captured eligible subject; delivery/read status does not itself set `CONFIRMED`.
- Responses do not expose plaintext token fields, provider secrets, or raw MIME in the implemented APIs.

## Gate assessment

| Gate | Status | Evidence / blocker |
| --- | --- | --- |
| G0 — Contract ready | Partial | Provider-neutral status flags exist, but a versioned usable F04 policy/recipient/evidence model is incomplete. |
| G1 — Local certification vertical | Blocked | DB invariant/SOD/evidence-validation gaps and no certification tests. |
| G2 — Local confirmation vertical | Blocked | Expiry, request-scope immutability, quorum notification, jobs, fake email token handoff, inbound/manual review, and tests are missing. |
| G3 — Close/downstream contract | Blocked | No close/reopen approval/clearing flow and no durable F05 contract publication. |
| G4 — External provider acceptance | Not attempted, correctly gated | Approved sender/mailbox, callback signing, recipient/quorum/delegation/SLA/retention decisions, SSO/OTP/MFA, and sandbox/live evidence remain external prerequisites. |

## External gates vs local work

The following remain external acceptance gates, not defects for refusing a real send: mail provider and monitored mailbox selection; sender/callback credentials; production recipient groups, quorum/delegation/SLA/retention policy; SSO/OTP/MFA policy; controlled-mailbox authorization; and sandbox/live delivery/reply evidence. The implementation appropriately exposes `NOT_CONFIGURED` for adapters, but local fake/recorded adapter tests and durable job contracts are still required before G2.

## Test automation disposition

No F04 test automation is present or approved as deferred. The following remain **local release blockers**, not external-provider deferrals: Testcontainers constraints/immutability/cross-scope tests; MockMvc authorization, SOD, token/replay/expiry/quorum tests; fake-adapter outbox/retry/no-silence tests; readiness/reopen/invalidation tests; and F01–F03 regression coverage with F04 paths exercised. See `CODE_ISSUES-BACKEND.md` for the actionable findings.
