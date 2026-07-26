# F04 Independent Code Analysis

**Analysis date:** 2026-07-26
**Scope:** current F04 Java services/controllers/security, V11 migration, React/TanStack routes/contracts, F04 integration/unit/E2E artifacts, and the existing F04 code/test review records. No product or test files were changed.

## Result

F04 remains **blocked for local G1--G3 release**. This pass found six additional P1 local blockers and two P2 hardening items that were not assigned an existing issue ID. They are recorded in [CODE_ANALYSIS_ISSUES.md](CODE_ANALYSIS_ISSUES.md). No P0 was established.

The separate provider/mailbox/SSO/F05 deployment approvals remain G4 external acceptance gates. They do not explain or defer the local blockers below.

## Verification performed

- `npm run typecheck` -- passed.
- `mvn -q -DskipTests compile` in `backend/` -- passed.
- Static trace of F04 request paths, transaction/locking order, PostgreSQL checks/triggers/indexes, state transitions, canonical hashes, DTO serialization, React query/mutation state, and F04 test fixtures.

Compilation and TypeScript success verify syntax and types only. They do not exercise the PostgreSQL, security, or real-browser contracts that fail in the reviewed F04 suites.

## New local blockers

| ID | Area | Finding | Gate impact |
|---|---|---|---|
| F04-ANL-001 | Authorization/data disclosure | A caller with read permission on any one project can retrieve the unfiltered confirmation request for the whole month. | G2 |
| F04-ANL-002 | State machine/quorum | `PROJECT_SPECIFIC` quorum cannot complete when one confirmer owns more than one project. | G2 |
| F04-ANL-003 | State machine/governance | Mixed multi-party decisions become a terminal reject/correction rather than a governed conflict. | G2 |
| F04-ANL-004 | SQL invariants/evidence lineage | Evidence links have neither a database scope check nor a criterion association; duplicates are also representable. | G1 |
| F04-ANL-005 | Java/React contract | The Java month response always emits `confirmationPreview: null`, so the real React screen contains no request-creation control. | G2 |
| F04-ANL-006 | Readiness/UI routing | Server-provided readiness CTAs use API-style or nonexistent SPA paths, so required corrective actions lead to dead routes. | G2 |

## Cross-cutting assessment

| Concern | Assessment |
|---|---|
| Architecture/cohesion | Controller-to-service boundaries and provider interfaces are clear, but service DTOs currently advertise flows which the implementation does not compose end-to-end: preview, project-specific confirmation, conflict governance, jobs, inbound/manual review, close, and F05 handoff. |
| Transactions/concurrency/idempotency | Month/request locks are a useful baseline. Existing issues still cover retry ordering, readiness-run race/dedupe, outbox/job absence, and no committed concurrent test lane; this pass adds an unrepresentable quorum state machine and conflict handling. |
| SQL/invariants/migration maintainability | V11 uses useful foreign keys, checks, partial-current indexes, and many immutability guards. The evidence relation is a material exception: it lacks a scope trigger/composite lineage and cannot encode criterion ownership. |
| Nullability/canonicalization/time | Existing findings already cover request expiry, captured policy TTL/due use, incomplete readiness manifests, injected clocks, and frontend offset loss. New P2 analysis adds unordered-list canonicalization for idempotency/hash input. |
| DTO/TypeScript contract | The fixture has known drift (`F04-FE-TEST-001/-002`). Independently, the production Java response always omits the preview required by the React create workflow, while TypeScript already anticipates a conflict-review state which V11 cannot store. |
| Performance/operability/observability | Existing reviews correctly identify missing local dispatch/retry/reminder/expiry/inbound workers, durable F05 publication, and correlation contract. The workspace hydration is additionally N+1 and should be batched before realistic month sizes. |
| Accessibility/responsive/state management | Existing frontend findings remain controlling: nested review route, stale controlled forms, inaccessible disabled validation, error-detail leakage, overflow, and fixture fidelity. New CTA routing evidence means even an accessible readiness blocker cannot navigate to its advertised corrective screen. |

## Reconciled findings (not renumbered)

The following were independently observed but already have an actionable owner/ID, so this analysis does not relabel them:

| Existing record | Independent confirmation |
|---|---|
| `F04-BE-001` | Confirmation/summary cross-scope and issued-request immutability gaps remain distinct from the new evidence-link invariant in `F04-ANL-004`. |
| `F04-BE-002`, `F04-BE-008` | Invalidation clearing, closure/reopen immutability, close/approval, and inbound/manual review remain absent. |
| `F04-BE-003`--`011` | SOD, action-project authorization, expiry/captured-policy use, terminal notification ordering, evidence policy, readiness manifest, local jobs/adapter, and F05 publication remain open. |
| `F04-BE-012` and `F04-TEST-003`--`009` | The now-present F04 tests still lack the required committed/concurrent/security/operability coverage. In particular no case covers the six P1s in this analysis. |
| `F04-FE-001`, `004`--`013` | Route outlet, retained retry keys, dirty-submit, criterion evidence UI, inbound/review contract, readable scope, time offset, stale forms, accessible validation, redaction, and tablet layout remain open. `F04-ANL-005/-006` are separate Java-to-React response/routing defects. |
| `F04-FE-TEST-001`--`005` | The intercepted fixture continues to hide the production null preview and route mismatch; its browser passes cannot establish the Java/Security/PostgreSQL contract. |
| `F04-TEST-008` | Services/security use `Clock.systemUTC()` while SQL authorization uses `CURRENT_DATE`; deterministic injectable-clock coverage remains an existing test/product concern. |

## P2 work to schedule after P1 remediation

- `F04-ANL-007`: canonicalization preserves request-list order even where ID lists are set-like, making a logical retry hash differently.
- `F04-ANL-008`: month workspace hydration performs per-deliverable nested JDBC queries (roughly six or more per deliverable) and needs batched reads/pagination.

## External gates

Keep G4 explicitly external: approved sender/domain and controlled inbox, callback signing and retention approval, recipient/delegation/quorum/SLA policy, SSO/OTP/step-up design, and deployed F05 consumer acceptance. A local fake/recorded dispatch/inbound/job vertical is still required for G2/G3 and is tracked by the existing backend issues; it is not an external-provider deferral.
