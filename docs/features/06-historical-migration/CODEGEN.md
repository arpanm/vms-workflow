# F06 — Code Generation Record

**Code-generation model:** `gpt-5.6-sol`

The local implementation adds:

- Flyway V17–V20 governed source/staging/reconciliation/provenance,
  compensation and immutable-policy schema;
- exact registry and deterministic RFC 4180 parser for all 14 templates;
- scoped authorization, upload, validation, idempotency, dependency, duplicate,
  dual-approval, commit, reprocess, rollback and retro services/APIs;
- React/TanStack Migration Center with typed API contracts and accessible
  operational states;
- bounded retry/recovery metrics and an opt-in validation worker that never
  acquires commit authority;
- JUnit/Spring/Testcontainers, Vitest, intercepted Playwright and a real local
  browser/Spring/Flyway/PostgreSQL system lane.

No Lovable, Supabase, browser database access or client-side privileged
provider dependency was introduced.
