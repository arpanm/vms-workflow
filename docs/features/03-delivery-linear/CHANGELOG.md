# F03 Changelog

## 2026-07-30 — Complete draft and revision editor

- Added repeatable deliverables, acceptance criteria, dependencies and
  contributor assignments to the React plan builder.
- Added a secured, tenant-scoped draft replacement API and edit route.
- Added PostgreSQL-enforced draft optimistic locking in Flyway V39 without
  weakening submitted/frozen/rejected evidence immutability.
- Exposed exact configured approvers, quorum and draft `editVersion` in the
  plan response so the editor never reconstructs authority from approval
  actions.

## 2026-07-26 — Provider-neutral local vertical

- Added delivery planning/version/baseline/approval, recorded Linear evidence,
  signed webhook and integration-health local paths.
- Added React planning, detail and health screens plus deterministic intercepted
  F03 browser-contract coverage.
- Hardened the local implementation through Flyway V8–V10 and focused review;
  all reviewed local P0 integrity findings are resolved by V10.
- Recorded evidence: 47 frontend tests, 49 backend Testcontainers tests and 26
  intercepted Playwright cases passing (including 8 F03/cross-feature cases).
- Preserved local P1 backlog and external Linear/mail/BFF gates. This release
  does not claim live provider connectivity or complete F03 delivery.

Related records: [FIXES.md](FIXES.md), [TEST_AUTOMATION.md](TEST_AUTOMATION.md)
and [POST_FIX_REVIEW.md](POST_FIX_REVIEW.md).

## 2026-07-29 — Delegation and cursor-reconciliation completion

- Bound delivery approval to shared-core, effective-dated delegations while
  retaining the configured authority holder, acting subject and delegation ID.
- Added V38 durable reconciliation checkpoints and immutable page-attempt
  evidence with bounded cursor pagination and retry/dead-letter state.
- Added a provider-neutral reconciliation adapter, disabled-by-default
  scheduler, explicit GraphQL partial-error semantics, and scoped status API.
- Added focused Testcontainers coverage for delegated approval and successful
  cursor/checkpoint evidence. Live Linear OAuth/GraphQL/webhook and real mail
  acceptance remain explicitly external.
