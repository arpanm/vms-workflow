# F04 post-fix independent review

**Review date:** 2026-07-26
**Scope:** F04 requirements 08, 09, 10, 13, 14, 16 and 21; V11/V12; Java/Spring
services, controllers, workers and integration tests; React contracts/routes and
Playwright fixtures; and the existing F04 review and remediation records.

## Release verdict

**NO-GO for a local F04 release.** No new P0 was found, but the P1 paths below
are fully local defects. They must not be deferred as provider, database-role,
or F05-consumer acceptance work.

The remediation is nevertheless substantial. The following final runs are
useful evidence for the code paths they exercise:

| Gate | Result | What it establishes |
| --- | --- | --- |
| `mvn clean verify` | 107 passed (58 F04, 49 regression) | V12 migrates on PostgreSQL and the covered Java lifecycle/security/worker paths pass. |
| `npm test` | 64 passed | React contracts, formatting, state, and intent-key behavior covered by the unit suite pass. |
| `npx playwright test e2e/certification.spec.ts` | 33 passed | The F04 browser journey works against its deterministic intercepted contract. |
| `npm run e2e` | 59 passed | The wider browser regression lane passes. |

Those counts are not a release discharge for an uncovered authorization,
lifecycle, or cross-scope branch. The browser suite is deliberately intercepted
and therefore is not browser-to-Java-to-PostgreSQL evidence.

## Exact local P1 blockers

| Finding | Evidence | Impact and required local correction |
| --- | --- | --- |
| `F04-BE-001` — source/lineage scope remains partial | `enforce_f04_summary_scope()` in `V12__certification_confirmation_hardening.sql` scopes the current summary sources but does not validate `monthly_certification_summaries.supersedes_id`. `CertificationWorkflowService.requestReopen()` persists each caller-supplied `impactedRecordIds` UUID as an invalidated `F04_OR_UPSTREAM_FACT` without proving that it is an F04 fact in the same month. | A cross-month/tenant predecessor can enter summary lineage, and a reopen can claim an unrelated impact. Add same-month scope validation for every lineage/reference field at the database boundary and resolve reopen impacts from authorized, typed in-scope facts rather than arbitrary UUIDs. |
| `F04-BE-002` — invalidation lifecycle can deadlock or be cleared without a tied correction | `requestReopen()` creates active invalidations before the approval decision. In `decideReopen()`, the reject branch only restores `CONFIRMED`/`CLOSED`; it creates no append-only resolution, leaving the invalidation effective `ACTIVE`. `resolveInvalidation()` accepts `CLEARED` when any later confirmed request exists; `correctionEvidence()` is not bound to the invalidated object, affected manifest, or an actual corrected successor. | A rejected reopen blocks readiness/closure until a separate manual action, while an unrelated later confirmation can clear an impact without proving correction. Rejecting a reopen must append compensating resolutions atomically. Clearing must require a scoped successor/evidence manifest that proves remediation of that exact invalidated fact. |
| `F04-BE-003` — separation of duties is incomplete for dual vendor/client identities | `CertificationAuthorizationService.requireItemDecision()` only rejects the submission's `created_by_subject`; it does not reject a current vendor party/owner who did not author the submission. `requireConfirmationAction()` has no separation-of-duties/vendor-affiliation check. The existing `tenantPartyProjectAndSeparationOfDutiesAreServerEnforced` test only covers the same principal authoring and certifying. | A person with vendor and client authority can certify another vendor user's submission, or contribute a confirmation action, despite the captured SOD policy. Enforce vendor-party/vendor-owner separation for certification and confirmation actions, from the captured policy/authority snapshot, and add both negative matrices. |
| `F04-BE-008` — inbound/manual review never becomes an attributable confirmation action | `CertificationReviewService.reviewInbound()` and `reviewManualEvidence()` only append review records, audit, and event facts. Neither creates a `business_confirmation_action`, and there is no authorized reviewed-inbound/manual action command. | The safe records and second review are useful, but verified reply/manual fallback cannot satisfy a quorum or create a package-visible `MANUAL_EVIDENCE`/reviewed reply decision. Implement a distinct, scoped promotion from a verified/reviewed record to `BusinessConfirmationService`'s action flow; preserve reviewer, represented/received timestamps, hashes, and source method. The live mailbox adapter remains an external gate, but this missing fallback transition is local. |
| `F04-BE-011` — an approved reopen does not prevent stale F05 publication | Approval inserts `f05_handoff_invalidations` and supersedes the request, but does not cancel its `f05_handoff_publish_jobs` row. `CertificationOperationsWorker.claimF05()` claims due jobs without requiring a still-`CONFIRMED` request or absence of a handoff invalidation, then invokes the F05 publisher. | A pending or reclaimed job can publish a readiness fact for a superseded confirmation after the reopen is approved. Atomically cancel/tombstone invalidated jobs; revalidate effective handoff/request state immediately before publish and before marking completion; specify the consumer's invalidation/compensation contract. |

## Local dispositions accepted after re-review

Subject to the blockers above, the following remediation claims are accepted as
locally implemented: primary request source scope and immutability, policy
append-versioning, criterion/evidence policy gates and exceptions, exact token
binding/expiry/replay handling, durable notification claims/retries/dead-letter
handling, quorum/conflict governance, immutable closure/reopen facts, F05
handoff persistence, current React route/API/error/idempotency behavior, and
the redacted inbound/manual-review UI. The audit did not identify a separate
remaining direct frontend P1.

The UI's inbound/manual screen accurately limits itself to restricted metadata
and a review decision. It must not be described as completing the required
manual/verified-reply confirmation flow until `F04-BE-008` is fixed.

## Test disposition

The reported 107/64/33/59 counts are retained as passing regression evidence,
not rejected. They lack the following P1 negative/lifecycle cases:

- dual vendor/client actor certifying a submission authored by another vendor,
  and contributing a confirmation action;
- direct-SQL scope rejection for cross-month summary `supersedes_id` and an
  API rejection for an out-of-scope reopen impact;
- rejected reopen automatically resolving its request-created invalidations,
  plus a rejection of `CLEARED` without a corrected successor for the exact
  invalidated fact;
- approved reopen before an F05 job claim and during a reclaim/publish race;
- verified inbound and second-reviewed manual evidence producing exactly one
  source-attributed confirmation action and applying normal quorum/conflict
  rules.

## Deployment and external acceptance gates

These are real gates, but are distinct from the local blockers above:

- `F04-BE-016` / `F04-SEC-009`: production database identities, least-privilege
  grants/RLS or equivalent, restricted-reader mapping, retention/legal-hold,
  and direct-role verification.
- `F04-SEC-008`: approved ingestion/scanning, controlled object storage,
  scoped audited artifact access, and retention acceptance.
- `F04-SEC-012`: strict production CORS/origin and edge header policy,
  future BFF/CSRF design if ambient authentication is introduced, secret
  management, and supply-chain/container checks.
- controlled sender/mailbox, callback signature material, provider retry and
  dead-letter operations, and live spoof/ambiguity/reply exercises.
- SSO/OTP/MFA/step-up, approved recipient/delegation/quorum policy, deployed
  F05 consumer acceptance, and a non-intercepted browser-to-Java-to-PostgreSQL
  system lane.

## Exit criteria

Local F04 can move to conditional deployment acceptance only after all five P1
findings have code and executable negative/race coverage, the full 107/64/59
regression gates remain green, and the provider/storage/database/F05/system
acceptance gates above are owned and evidenced in their controlled environments.

## Final V13 P1 re-review — 2026-07-26

**Local P1 verdict: GO.** This supersedes the local-P1 portion of the original
NO-GO above. Independent review found no remaining local P0/P1 in
`F04-BE-001`, `F04-BE-002`, `F04-BE-003`, `F04-BE-008`, or `F04-BE-011` for
new V13 writes. The production/deployment decision remains conditional on the
separate gates below and in `CODE_ISSUES.md`.

| Finding | Final disposition |
| --- | --- |
| `F04-BE-001` | V13 scopes summary predecessors; reopen impacts resolve to exactly one supported same-month typed fact and are independently enforced by trigger. |
| `F04-BE-002` | Rejecting a reopen appends compensating resolutions; clearing requires the exact direct successor/version and later confirmed scoped request at both service and DB boundaries. |
| `F04-BE-003` | Captured-policy SOD now rejects active vendor affiliation/frozen vendor ownership for item and confirmation actions, including represented reviewed-evidence actors. |
| `F04-BE-008` | Reviewed authenticated inbound and distinct approved manual evidence promote once to attributable normal-quorum actions, with request/source/review/hash/time binding. |
| `F04-BE-011` | Handoff invalidation tombstones work; claim, pre-publish, and completion revalidation fence stale jobs, and immutable compensation lineage is emitted. |

Independent verification ran `mvn -Dit.test=CertificationPersistenceIT,CertificationGovernanceIT,CertificationWorkflowIT,BusinessConfirmationIT,CertificationReviewIT,CertificationF05ReopenConcurrencyIT verify` in `backend/`: **109 Failsafe integration tests plus 2 Surefire unit tests passed** (0 failures, 0 errors, 0 skipped). See `FINAL_P1_REVIEW.md` for evidence and the pre-existing-V13 data-upgrade qualification.
