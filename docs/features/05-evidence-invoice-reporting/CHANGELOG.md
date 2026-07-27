# F05 — Change log

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
