# F07 — Final Review Status

**Current local decision:** `LOCAL_ENGINEERING_PASS / COMMIT_EVIDENCE_PENDING`

**Current production decision:** `NO-GO / ACTION_REQUIRED`

## What is implemented

The active worktree contains the F07 Java/PostgreSQL hardening vertical:
least-privilege database roles, HTTP/browser controls, retention/privacy/legal
hold, server-authoritative feature flags, observability/readiness, capacity
guards, accessibility, release evidence, supply chain, rollout/rollback and
backup/restore harnesses.

Independent backend and operations reviews found substantive P0/P1 issues. The
current code contains remediations documented in [FIXES.md](FIXES.md).
Focused backend, capacity, local-system, static, browser and definitive complete
Maven evidence is green. Final Terra review closed with no P0–P3 finding.
Commit-bound release evidence is not yet recorded.

## Evidence available now

- pre-final backend gate: **73 unit + 45 integration**, all passing;
- capacity gate: **73 unit + 2 capacity**, all passing; dashboard 101ms,
  check-in p95 404ms, replay p95 69ms, 10k search p95 2ms and 300k report p95
  9ms;
- ordered F07 local-system E2E: **7/7**, covering
  E2E-01/02/03/04/05/07/10;
- finance local-system E2E: **4/4**, covering E2E-06/E2E-09;
- migration local-system E2E: **6/6**, covering E2E-08;
- browser matrix: first run **268/274**, exact failed slice **7/7**, final full
  rerun **274/274**, with zero failed/skipped/flaky;
- static/typecheck/lint/diff and F07 harness/schema gates: **PASS**;
- frontend exact result: typecheck pass; lint 0 errors/6 non-blocking Fast
  Refresh warnings; Vitest 24 files/92 tests; build pass with 3,006 modules and
  a 586.90 kB largest-chunk optimization advisory; diff-check pass;
- production Flyway schema: **V1–V33**; V1000+ remains test-fixture-only;
- complete Maven verification R3: **PASS — 73 unit + 217 integration
  (290/290)**, zero failures/errors/skips, BUILD SUCCESS in 03:21;
- preserved Maven R2: 73 unit pass and 215/217 integration pass in 39:23, with
  two approximately 16–17 minute host starvation/clock-leap pauses. Its two
  worker counts came from non-dedicated test-database state; the dedicated
  worker database correction passes in R3.
- final independent Terra review: **CLOSED**, no P0–P3 finding.

This is strong local development evidence. It is still not production
acceptance.

## Required local closure

1. Preserve R2 and R3 in commit-bound evidence.
2. Execute commit-bound migration compatibility, supply-chain, backup/restore
   and rollback/release evidence against the exact local commit.
3. Keep the release verdict fail-closed for every missing/failed/expired or
   external record.

## External release gates

Production still requires real identity/BFF, provider/scanner/storage/email,
secret-management, observability/on-call, capacity/soak, accessibility, legal/
privacy, backup/PITR/DR, UAT/training/Procurement and named approval evidence.
The repository must not simulate those approvals.

The authoritative open list is [FINAL_ISSUES.md](FINAL_ISSUES.md); operational
execution is indexed in [RUNBOOK.md](RUNBOOK.md).
