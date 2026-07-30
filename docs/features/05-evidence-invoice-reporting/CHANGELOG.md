# F05 — Change log

## 2026-07-30 — Codegen hardening and recovery

- Versioned an explicit exceptionable-readiness rule set and made invoice
  document and package-manifest integrity blockers non-waivable regardless of
  policy configuration.
- Kept exact invoice/package/readiness/rule/policy binding, distinct
  authenticated approval, expiry, revoked-authority and cross-tenant controls;
  replaced the invented test-only exception rule with a policy-declared
  business readiness rule.
- Fenced export completion on a still-live lease and prevented stale failure
  handling from overwriting a replacement claim. Added an expired-claim
  restart/idempotency regression asserting one artifact and one ready event.
- The integrated Maven attempt executed 340 tests with 2 failures and 1 error;
  the exact Finance recovery then passed **1/1**. The aggregate is intentionally
  not relabeled 340/340.
- External provider, production, performance/DR and G4 acceptance gates remain
  unchanged.
- The combined browser attempt remains **287/292**, with its exact failed slice
  passing **5/5**. F05 accessibility (**3/3**, intercepted browser) and finance
  system (**4/4**, local Vite/Spring/Flyway/PostgreSQL) remain distinct evidence.
- F07-T057 soak and controlled DR remain outside this F05 local recovery.

## 2026-07-30 — Local-evidence follow-up

- Added a scanner-derived blocked-readiness assertion for a quarantined EICAR
  artifact and a committed two-caller package-generation race assertion.
- The committed concurrency lane passed **2/2**, scanner-readiness workflow
  passed **1/1**, accessibility browser lane passed **3/3** (axe
  serious/critical, keyboard entry, tablet no-overflow), and the isolated
  Spring/Flyway/PostgreSQL system lane passed **4/4**.
- Historical full-suite counts remain historical; G4 production acceptance
  remains external.

## 2026-07-27 — Evidence, invoice and reporting implementation

- Added F05 schema, services, APIs, React finance routes, package/invoice/
  Procurement/payment/report/export workflows and provider-neutral local
  adapters.
- Added deterministic canonical manifests, private-byte hash/scan controls,
  immutable lineage, F04 contract/invalidation consumption, package shares and
  export worker retry/dead-letter/replay.
- Added backend tests, focused frontend Vitest evidence and authored F05
  Playwright journeys.
- Completed independent review/static/security passes, recorded historical
  issues and applied local remediations.
- Consolidated feature architecture, API, UI, metric, runbook and SDLC
  documentation.
- Added the isolated real-system Playwright lane with local signed JWT/JWKS,
  Vite, Spring Security/API, Flyway and PostgreSQL 18; final result is 3/3.
- Recorded the green F05-focused Spring/Flyway/PostgreSQL integration gate:
  **34/34 passing**.
- Fixed three defects found only through that lane: finance month schema-version
  reads, resolved-query approval transition V15, and share validity-window plus
  share-event aggregation V16/service handling.

- Completed full local gates: backend **154/154**, Vitest **88/88**, combined
  intercepted Playwright **69/69**, isolated system Playwright **3/3**, and
  typecheck/lint/build.

**Not a production release tag:** performance/scale evidence and all G4
external acceptance remain `ACTION_REQUIRED`.

## 2026-07-30 — Completion audit

- Reconciled the Java dashboard metric response with the React adapter.
- Replaced first-page-derived dashboard counts with full authorized-scope
  PostgreSQL aggregates and corrected live/unavailable semantics.
- Added permanent Java, React and real-system E2E regressions plus expired and
  checksum-mismatched export download guards.
- Added a non-transactional two-worker race proving one committed export
  artifact/event/outbox effect.
- No schema migration was needed. External provider, Procurement and
  production acceptance gates remain unchanged.
