# F07 — Code Analysis

## Architecture and dependency analysis

The feature stays within the controlling Java/PostgreSQL architecture:

- Vite/React renders and invokes typed HTTP contracts;
- Spring Boot owns identity, scope, authorization, validation and workflow
  transactions;
- Flyway owns forward-only PostgreSQL schema;
- local Node scripts orchestrate evidence and operations, not business data.

No current implementation depends on Lovable, Supabase, Cloudflare or direct
browser database access. F07 adds no client-side permission authority.

## Static design checks

- API observability tags use route templates/status classes, not raw object IDs
  or personal labels.
- SQL uses fixed statements or constrained identifiers; database functions
  have fixed `search_path`, and PUBLIC defaults are revoked.
- retention record classes and feature-flag scope types are allowlisted;
  retention schedules are configured facts rather than invented policy.
- feature dependencies are resolved server-side and cycles are rejected.
- errors are reduced to safe client messages/correlation references and
  exception logs no longer print complete unexpected stack payloads.
- backup/release evidence uses canonical serialization, hashes, independent
  manifest authentication, exclusive paths and commit binding.

## Capacity analysis

V24 adds targeted indexes for the scoped reporting/search workload.
`F07CapacityPerformanceIT` seeds synthetic data and checks:

- 26-person bounded concurrent attendance/idempotent replay;
- 10,000-person scoped search/reporting;
- measured latency thresholds in a local Testcontainers environment;
- expected index use and absence of named critical sequential scans.

This is a local algorithm/query-plan guard. It is not production headroom,
autoscaling or a 99.9% availability claim.

The final capacity rerun passed 73 unit plus 2 capacity tests. Observed local
measurements were dashboard read 101ms (after the preserved 3,202ms failure),
check-in p95 404ms, replay p95 69ms, 10,000-employee search p95 2ms and
300,000-row report p95 9ms.

## Final static and system analysis

- Frontend/static gates pass: typecheck; lint 0 errors/6 non-blocking Fast
  Refresh warnings; Vitest 24 files/92 tests; build 3,006 modules with 586.90
  kB largest-chunk advisory; diff-check.
- The focused backend gate passes 73 unit + 45 integration tests.
- F07/F05/F06 local-system suites pass 7/7, 4/4 and 6/6.
- The complete browser matrix passes 274/274 after the preserved 268/274 and
  exact 7/7 rerun history.
- Production migrations are V1–V38; V1000+ scripts are test fixtures only.
- Complete Maven R3 is **PASS**: 73 unit + 217 integration (290/290), zero
  failures/errors/skips, BUILD SUCCESS in 03:21. R2's 215/217 IT result was a
  shared test-database isolation problem; the dedicated worker database passes.

## Analysis limitations

Exact commit-bound Semgrep/Trivy/npm/license/SBOM execution, memory/connection soak,
production query distribution and real provider
behavior remain pending or `ACTION_REQUIRED`. See
[SECURITY_ANALYSIS.md](SECURITY_ANALYSIS.md) and
[FINAL_ISSUES.md](FINAL_ISSUES.md).

The recorded analysis predates V39/V40 and the current release-control
hardening. A candidate release must rerun static/system analysis and bind the
five-dimension review to the exact candidate commit; ancestry alone is not
accepted by the release gate.
