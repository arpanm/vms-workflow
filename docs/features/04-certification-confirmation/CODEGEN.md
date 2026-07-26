# F04 Code Generation — Consolidated Record

**Status:** Local provider-neutral vertical implemented; external acceptance remains open.

F04 adds certification and confirmation to the Java 25 / Spring Boot / PostgreSQL application and the Vite React client. It consumes immutable F02 attendance and F03 frozen-plan/Linear evidence, creates versioned F04 facts, and never treats Linear status, a transport receipt, silence, elapsed time, or an auto-reply as approval.

The stack remains Java/PostgreSQL with Flyway and Vite React/TanStack. There are no Lovable or Supabase dependencies, migrations, clients, or browser-side provider credentials.

## Implemented boundary

- Additive Flyway migrations `V11` and `V12` model policy versions, submissions, certification rounds and decisions, readiness, confirmation scope/actions, immutable outbox/inbound/manual-review evidence, closure/reopen lineage, invalidations, and the versioned F05 readiness handoff.
- Java services enforce server-derived authority, expected version/ETag, idempotency, canonical hashes, append-only facts, scoped confirmation contributions, quorum/conflict governance, expiry, replay protection, worker retries/dead-letter/replay, and redacted audit/security events.
- Provider adapters are deliberately local/recorded: email, evidence storage, and F05 publication surface explicit configured states and durable local contracts, not live provider completion.
- React provides vendor submission, assigned product-owner review, readiness/confirmation governance, and authenticated in-app response routes. It renders safe metadata only and uses the Java DTO/header contract.

Detailed implementation records: [backend](CODEGEN-BACKEND.md) and [frontend](CODEGEN-FRONTEND.md). The independent implementation map is [CODE_ANALYSIS.md](CODE_ANALYSIS.md).

## Evidence boundary

Root-verified final evidence for this worktree is 111 backend tests (109 integration plus 2 unit), 64 frontend tests, and 59 Playwright browser-contract tests. The browser lane intercepts APIs; it is not browser-to-Java-to-PostgreSQL proof.

See [TEST_AUTOMATION.md](TEST_AUTOMATION.md), [API_DOCUMENTATION.md](API_DOCUMENTATION.md), [UI_DOCUMENTATION.md](UI_DOCUMENTATION.md), and [FIXES.md](FIXES.md).
