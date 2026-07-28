# F06 — Migration Center User Guide

Open **Governance → Historical migration** as a scoped migration lead.

1. Download the exact template and follow the displayed dependency order.
2. Select the template, scope, source type/confidence and CSV. Upload creates a
   dry run only.
3. Run validation. Review totals and filter row errors/warnings. The downloadable
   report is formula-safe and excludes raw sensitive values.
4. Correct the source and re-upload, or use an authorized conflict decision.
   Reprocess rejects never repeats committed rows.
5. Review the exact reconciliation hash, expected/imported employee-days,
   low-confidence disclosure and domain coverage.
6. Obtain migration-lead and distinct governance/business approval on that exact
   hash. A changed report invalidates stale sign-offs.
7. Choose the valid-rows-only policy when uploading. That choice is immutable
   for the job; the commit screen shows and explicitly reaffirms it. When
   enabled, rejected rows remain visible and reprocessable. When disabled, any
   late conflict aborts the whole commit.
8. Use rollback only before downstream evidence consumes the batch. Otherwise
   follow the displayed reopen/versioned-correction path.
9. For a failed job, **Retry safe recovery** resumes only scan/validation work
   with the current version and an operator reason. For cancelled history it
   creates a linked replay job; it never reopens the cancelled record or
   retries commit.
10. A retro commitment/certification/confirmation is clearly labelled historical
   but records the real current decision time. If the original approver is
   unavailable, supply valid delegation/replacement authority.

The workspace includes loading, empty, denied, stale/version-conflict, invalid,
partial, failed, cancelled and rolled-back states. Statuses use text and icons;
tables, forms and actions are keyboard operable. `ACTION_REQUIRED` means a real
production rehearsal or source-owner decision is not configured—not success.
