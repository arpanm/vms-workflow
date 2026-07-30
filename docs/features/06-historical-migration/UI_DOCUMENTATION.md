# F06 — Migration Center User Guide

## Final integrated reconciliation — 2026-07-30

The documented UI includes tenant CSV/XLSX templates, row/conflict handling,
durable job progress, retro outcomes and consumed-package correction routing.
Focused accessibility passes 3/3 and the migration system flow passes 6/6.
Production rehearsal and representative-user approval remain external.

Open **Governance → Historical migration** as a scoped migration lead.

The row workbench pages redacted rows, filters state and offers explicit
keep-existing, reject or versioned-supersede decisions for conflicts. The
historical inbox records approve/reject/cancel at authenticated current time,
shows Procurement delivery separately, and displays month readiness blockers
before certification, confirmation and confirmed transitions.

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
