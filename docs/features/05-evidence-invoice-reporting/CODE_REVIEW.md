# F05 — Code-review consolidation

**Current gate:** No final GO decision until the coordinating regression run
verifies the post-review fixes.

## Review history

| Pass | Record | Outcome at that point | Disposition |
| --- | --- | --- | --- |
| Initial independent backend review | [CODE_REVIEW-BACKEND.md](CODE_REVIEW-BACKEND.md) | NO-GO; 21 findings | Historical baseline; its issues drove the implementation fixes. |
| Frontend review | [CODE_REVIEW-FRONTEND.md](CODE_REVIEW-FRONTEND.md) | Review observations and UI boundary | Retained as frontend evidence. |
| Post-fix backend review | [POST_FIX_REVIEW-BACKEND.md](POST_FIX_REVIEW-BACKEND.md) | NO-GO; 13 findings | Historical intermediate result. |
| Final backend re-review | [FINAL_REVIEW-BACKEND.md](FINAL_REVIEW-BACKEND.md) | NO-GO; P0/P1/P2 findings | Its exception-route and package-PDF observations were corrected after this static pass; fresh test/re-review evidence is still required. |

## Current review posture

The implementation has addressed the documented local findings around F04
contract consumption, private bytes/scan lifecycle, immutable lineage,
sharing/revocation, payment lineage, policy resolution, export worker/retry,
finance scope, rate limiting, query visibility and error redaction. The latest
source changes must now be verified together rather than treated as a review
approval. In particular, the final validation must demonstrate:

1. blocked readiness → authorized exception → derived exception readiness →
   submission/review with distinct-second-approver and expiry denial cases;
2. report-specific, authority-snapshot-bound field projection/masking;
3. package human-readable PDF evidence rows and output integrity for all
   formats; and
4. no regression in cross-scope denial, immutable DB guards, cursor behavior,
   scanning, rate limiting, outbox and worker replay.

The authoritative historical issue detail remains in
[CODE_ISSUES.md](CODE_ISSUES.md); resolution evidence must be added only after
the fresh run.

## 2026-07-30 focused re-review

The audit found one additional local contract/product defect not captured by
the historical green counts: dashboard metric fields differed between Java and
React, and the Java aggregate silently counted only page one. Both are fixed
with focused PostgreSQL and React evidence. The audit did not convert the
overall historical review to a fresh full-regression GO; natural exception
setup, independent committed concurrency/lease-loss, performance/DR and G4
external acceptance remain separate gates.
