# F07 — Code Generation Record

F07 hardens the existing Java/PostgreSQL product and adds locally executable
release controls. It does not replace the application with a different stack
and does not treat a local pass as production approval.

## Implemented work

| Area | Primary implementation | Contract |
|---|---|---|
| HTTP security | `SecurityConfig`, `SecurityHeadersFilter`, `RequestSizeLimitFilter`, `CoreRateLimitFilter`, `OutboundUriPolicy`, `ApiExceptionHandler` | Exact-origin bearer CORS, safe headers, bounded bodies, trusted-proxy-aware rate limits, HTTPS host allowlists and correlation-safe RFC 7807 errors. |
| Database boundary | Flyway V21 and `DatabaseRoleGuard` | Separate NOLOGIN migration, runtime, reporting, worker and backup capabilities; secure defaults; fixed function `search_path`; runtime startup guard. |
| Retention/privacy | Flyway V22, `RetentionPrivacyController`, `RetentionPrivacyService` | Versioned schedules, deterministic dry runs, capability expiry proofs, bounded retry/dead-letter recovery, data classification and governed legal holds. |
| Feature flags | Flyway V23, `FeatureFlagController`, `FeatureFlagService`, `FeatureFlagAuthorizationService` | Immutable server-side definitions/versions, SYSTEM/ORGANIZATION/ENGAGEMENT scope, windows, dependencies and audited evaluation. A flag never grants permission. |
| Capacity | Flyway V24 and `F07CapacityPerformanceIT` | Targeted indexes plus synthetic 26-user and 10,000-record local profiles/query-plan assertions. |
| Observability | `ApiObservabilityFilter`, `OperationalReadinessMetrics`, `WorkflowReadinessHealthIndicator`, `OptionalProviderHealthIndicator` | Route-template metrics, low-cardinality operational gauges, minimal readiness and explicit optional-provider degradation. |
| Browser safety/accessibility | `safe-error.ts`, root shell/sidebar/route/style changes and `e2e/f07-accessibility.spec.ts` | Safe error presentation, support reference, skip link, focus management, named controls, mobile reflow and reduced motion. |
| Release/DR/supply chain | `scripts/f07`, F07 JSON policies, GitHub workflow and operations runbooks | Commit-bound structured evidence, source/live migration comparison, provenance/SBOM/scanning, exclusive encrypted backup/restore evidence and fail-closed release decisions. |

## Generated database lineage

- V21 introduces the capability-role policy and rate-limit buckets, transfers
  owned objects under a bounded lock timeout and protects Flyway/audit/secrets.
- V22 adds retention schedules, runs, candidates, results, proofs, legal-hold
  transitions and classification. No statutory retention duration is seeded.
- V23 adds server-authoritative feature flags, scoped immutable versions,
  dependency edges, evaluation audit and platform flag authority.
- V24 adds bounded indexes used by the local capacity/query-plan tests.

The migration login is externally provisioned. The administrator bootstrap in
`scripts/f07/bootstrap-database-roles.sql` must run before Flyway in a
controlled environment; the application pool never uses migration credentials.

## Boundary retained

The browser remains a Vite/React client of `/api/v1`; Spring Boot derives
identity and authorization from JWT plus PostgreSQL assignments. PostgreSQL is
the only application database. No Lovable, Supabase, Cloudflare or direct
browser-to-database dependency was introduced.

## Verification state at initial code-generation handoff

At this historical handoff, focused unit/integration and browser accessibility
runs had been observed, while the consolidated lanes were still pending.
Those later results—including the exact post-remediation supply-chain pass and
Maven R4 290/290—are recorded in [TEST_AUTOMATION.md](TEST_AUTOMATION.md),
[CHANGELOG.md](CHANGELOG.md) and [FINAL_REVIEW.md](FINAL_REVIEW.md).
Production OIDC, secret manager, scanner, storage, observability/on-call,
backup/PITR, capacity/soak, legal/privacy and named approvals remain
`ACTION_REQUIRED`.

See [FIXES.md](FIXES.md) for the post-review code-generation corrections.
