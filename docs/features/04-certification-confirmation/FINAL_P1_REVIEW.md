# F04 final P1 independent review

**Review date:** 2026-07-26
**Scope:** V13, the five local P1 findings in `POST_FIX_REVIEW.md`, their
Java services/workers, and focused PostgreSQL/Testcontainers coverage.

## Verdict

**GO for the local P1 remediation.** No open local P0/P1 remains in the five
remediated new-write paths. This is a conditional deployment-acceptance
verdict, not a production deployment approval: the external, storage,
database-role, provider, F05-consumer, and full-system gates remain open.

For an environment that has already accepted F04 traffic under V11/V12, apply
the V13 data-upgrade check recorded in `CODE_ISSUES.md` before rollout. V13
protects future writes; database triggers do not rewrite historical bad
lineage or old unresolved invalidations.

## Finding dispositions

| Finding | Independent disposition | Evidence reviewed |
| --- | --- | --- |
| `F04-BE-001` | Fixed for new writes. | V13 scopes `supersedes_id` to the summary month, `f04_fact_month` closes invalidation object types to supported F04 facts, and `resolveReopenImpacts` resolves each supplied UUID to exactly one same-month typed fact before insertion. The summary direct-SQL and out-of-scope API cases pass. |
| `F04-BE-002` | Fixed. | Reopen rejection atomically appends `SUPERSEDED` resolutions. `CLEARED` is derived from an exact direct successor plus a later confirmed request, and V13 independently validates object type, month, version, successor edge, and confirmation scope at the database boundary. |
| `F04-BE-003` | Fixed. | Item and confirmation decisions apply captured-policy SOD to both active vendor affiliation and frozen vendor ownership. Reviewed evidence reuses confirmation authorization for the represented actor. The other-vendor submission and confirmation-action dual-role negative cases pass. |
| `F04-BE-008` | Fixed. | Accepted verified inbound replies and distinct approved manual evidence promote exactly once into a request-version-bound confirmation action. The promotion records source/review, represented time, actor, hash, and method; it applies normal ordering, quorum, conflict, terminal, and F05 logic. V12 also enforces request/month and scan-eligible manual-artifact scope. |
| `F04-BE-011` | Fixed. | An approved reopen inserts the immutable handoff invalidation, whose V13 trigger tombstones pending/claimed/completed/dead-letter jobs. Claim, pre-publish lock/revalidation, and completion revalidation require a confirmed request with no invalidation. The worker holds the job lock while publishing, serializing approval; a publication that committed before approval is fenced by the compensation event/consumer contract. |

## Verification performed

`mvn -Dit.test=CertificationPersistenceIT,CertificationGovernanceIT,CertificationWorkflowIT,BusinessConfirmationIT,CertificationReviewIT,CertificationF05ReopenConcurrencyIT verify` completed successfully in `backend/`. The configured Failsafe lane reported **109 integration tests**, and Surefire reported **2 unit tests**: **111 passed, 0 failed, 0 errors, 0 skipped**.

The exercised evidence includes:

- direct-SQL cross-month summary-predecessor rejection and API rejection of an
  unknown reopen impact;
- rejection compensation, false-clearing rejection, and exact-successor
  clearing;
- both dual vendor/client SOD negatives;
- inbound/manual reviewed promotion, source attribution, quorum terminal
  state, and idempotent replay; and
- pre-claim cancellation plus the expired-claim reclaim/reopen concurrency
  case, final job tombstone, effective invalidation, and post-fence refusal.

The concurrent F05 test is a useful integration race test but not a proof of
external consumer compensation. The external contract remains mandatory: the
consumer must revoke or compensate the matching readiness fact before any
downstream billing or invoice use.

## Remaining gates, correctly classified

These are not local P1 regressions in the five remediated paths:

- **Deployment/data:** least-privilege database identities/RLS, restricted
  reader mapping, retention/legal hold, production CORS/edge/secret and
  supply-chain controls, plus the pre-existing-V13 data audit noted above.
- **Storage/provider:** approved scanning and object storage controls;
  sender/mailbox/callback configuration; live ambiguity, spoofing, retry, and
  dead-letter exercises.
- **External/system:** SSO/OTP/MFA/step-up and approved delegation/quorum
  policy; deployed F05 consumer compensation acceptance; a non-intercepted
  browser/BFF/Java/PostgreSQL system run.
