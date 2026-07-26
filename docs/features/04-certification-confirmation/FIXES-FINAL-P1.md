# F04 final local P1 remediation

**Remediation date:** 2026-07-26
**Scope:** The five local P1 blockers retained by
`POST_FIX_REVIEW.md`: `F04-BE-001`, `F04-BE-002`, `F04-BE-003`,
`F04-BE-008`, and `F04-BE-011`.

## Production corrections

| Finding | Final local correction |
| --- | --- |
| `F04-BE-001` | V13 extends the database summary-scope gate to `supersedes_id`. Reopen impacts are resolved server-side to one supported typed fact in the same engagement month; unknown, ambiguous, duplicate, and cross-month UUIDs are rejected. A database trigger independently enforces the typed fact/month/reopen relationship. |
| `F04-BE-002` | Rejecting a reopen now appends a `SUPERSEDED` resolution for every invalidation created by that reopen in the decision transaction. An approved correction cannot use generic supersession. `CLEARED` requires the exact direct successor object, its exact version, and a later confirmed request whose immutable scope contains that successor; V13 rechecks all of those conditions at the database boundary. |
| `F04-BE-003` | Item decisions and confirmation actions now apply captured-policy SOD to active vendor-organization affiliation and the frozen deliverable vendor owner, in addition to the original submitter check. The same check is applied to the represented actor when reviewed inbound/manual evidence is promoted. |
| `F04-BE-008` | Accepted authenticated explicit replies and distinctly approved manual evidence now create exactly one request-version-bound `business_confirmation_action`. Promotion resolves exactly one captured eligible actor/project, rechecks current authority and SOD, preserves the review/source ID, reviewer, represented/received timestamp, evidence hash, verification method and action hash, and then applies the existing ordered/quorum/conflict/terminal/F05 flow. Transport, recording, quarantine, or evidence upload still never confirms before the authorized review. |
| `F04-BE-011` | F05 jobs have a durable `CANCELLED` tombstone tied to the handoff invalidation. Approval cancels pending, claimed, completed, or dead-letter work; claims require a confirmed request and no invalidation. The worker locks and revalidates immediately before calling the publisher and rechecks before completion, serializing publish against reopen approval. Each invalidated handoff also emits immutable `certification.f05-handoff.invalidated.v1` compensation lineage. |

## Reviewed-evidence action contract

A promotion is valid only when all of the following hold:

1. The source has an append-only accepting review.
2. Inbound mail is request-bound, explicit, callback/authentication verified,
   and resolves by hashed sender address to exactly one captured eligible
   actor/project. Manual evidence is request-bound, scan-eligible, represented
   by an eligible sender, and approved by a reviewer distinct from its recorder
   and represented actor.
3. The represented actor retains current confirmation authority and passes the
   captured SOD policy.
4. The request is the exact stored version, is still awaiting response, and is
   before its captured deadline.
5. The source may create only one immutable promotion/action. API retries return
   the original review and cannot contribute a second quorum action.

The resulting action uses `VERIFIED_EMAIL_REPLY`/`VERIFIED` or
`MANUAL_EVIDENCE`/`MANUAL_REVIEWED`. It participates in the same ordered
quorum, conflict-governance, terminal notification, readiness, and handoff path
as an authenticated in-app action.

## F05 invalidation and compensation contract

The readiness publication identity is the immutable handoff plus
`confirmationRequestId`, confirmation scope checksum, readiness input hash, and
package hash. After `certification.f05-handoff.invalidated.v1`, an F05 consumer
must treat the matching readiness fact as revoked and must stop or compensate
downstream processing before billing/invoice use. A local `CANCELLED` job and
`effective_f05_certification_handoffs.effective_status = 'INVALIDATED'` are the
producer-side fences; neither a transport success nor a prior `PUBLISHED`
attempt overrides the invalidation event.

## Executable coverage

Focused Testcontainers/MockMvc coverage now includes:

- cross-month summary predecessor rejection and out-of-scope reopen impact
  rejection;
- rejected-reopen compensating resolution, false clearing rejection, and
  successful exact-successor/version clearing;
- a dual vendor/client actor attempting item certification for another vendor
  author and attempting a confirmation contribution;
- reviewed verified inbound and second-reviewed manual evidence creating one
  source-attributed action, normal terminal quorum, and idempotent retry;
- pre-claim cancellation plus an independently committed expired-claim
  reclaim/reopen race, final job tombstoning, effective handoff invalidation,
  compensation event, and refusal to execute after the fence.

## Verification

- `mvn clean verify` — **PASS**, 111 tests (109 Failsafe integration tests plus
  2 Surefire unit tests), 0 failures, 0 errors, 0 skipped.
