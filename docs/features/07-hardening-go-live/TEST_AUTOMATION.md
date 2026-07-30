# F07 — Test Automation

## Final integrated reconciliation — 2026-07-30

Current automation passes system 7/7, self-test 9/9 (45.037 s), operations
15-runbook/6-alert validation, all 43 migration schemas, rollout schema and the
eight-feature SDLC model-separation check. The release-schema wrapper itself is
not claimed as passed: sandbox bind failed `EPERM` on `127.0.0.1`, and its
silent escalated retry was aborted after 467 s; the underlying gates pass.
Maven/browser aggregate failures and focused recoveries remain separate.

F07 uses separate lanes because intercepted browser contracts, local
Java/PostgreSQL integration and production acceptance prove different things.

## Executable lanes

| Lane | Command | Primary coverage |
|---|---|---|
| Frontend quality | `npm run typecheck`, `npm run lint`, `npm run test`, `npm run build` | Type contracts, safe error redaction and frontend build integrity. |
| Backend unit/integration | `mvn -B -f backend/pom.xml verify` | Flyway V1–V38, Spring Security/HTTP, PostgreSQL roles, retention/legal hold, flags/metrics, telemetry redaction, provider authority, workers, lineage and capacity. |
| Focused F07 database bootstrap | `mvn -B -f backend/pom.xml -Dit.test=F07MigrationBootstrapIT verify` | A constrained migration login upgrades a fresh database without giving runtime migration authority. |
| Browser contract regression | `npm run e2e` | Existing product journeys plus the F07 five-browser/project accessibility matrix with deterministic intercepted APIs. |
| Prior-feature local systems | `npm run e2e:finance:system`, `npm run e2e:migration:system` | Real local browser/Spring/Flyway/PostgreSQL regression for F05 and F06. |
| Post-deploy aggregate | `node scripts/f07/post-deploy-regression.mjs --evidence-dir <raw-evidence>` | Re-parses current-commit machine evidence and fails unless E2E-01–10, audit/outbox and rollback integrity all pass. |
| Harness self-test | `npm run f07:self-test` | Adversarial release evidence, path, manifest, migration, rollout and backup/restore validation. |
| Traceability/review controls | `npm run f07:self-test` | Exact 85-task/76-test inventory; differentiated requirement/PRD/schema/API/UI/runbook/rollback policy; five required independent review dimensions and explicit open-local-P0/P1 ledger. |
| Release/ops schemas | `npm run f07:ops:check` | Checked-in JSON/schema/runbook consistency and fail-closed local decision. |
| Supply chain | `npm run f07:supply-chain:run` | Trivy, npm audit, Semgrep, license checks, artifacts and CycloneDX SBOMs. |

The review-control self-test rejects an incorrect schema/type, a 40-character
Git tree object used as `reviewedThroughCommit`, a reviewed commit outside the
validated release ancestry, an ancestor-only review offered for a release
decision, a missing structured closure dimension and a runbook fragment
without an explicit anchor. Current focused results:
`f07:self-test` **9/9 passed** and `f07:ops:check` **1/1 passed**.

The GitHub workflow wraps each command in structured evidence, binds it to
clean commit provenance and assembles a candidate manifest. The release gate
must remain NO-GO when a mandatory local result is missing/failed or an
external item is `ACTION_REQUIRED`.

## Stable F07 browser cases

`e2e/f07-accessibility.spec.ts` owns these permanent IDs:

- `F07-A11Y-001A`: shell, skip link, focus target, safe 404 and axe gate;
- `F07-A11Y-001B`: labelled employee attendance and 390px mobile reflow;
- `F07-A11Y-001C`: certification governance axe gate;
- `F07-A11Y-001D`: finance workspace at tablet width;
- `F07-A11Y-001E`: named Migration Center controls;
- `F07-A11Y-002A`–`002F`: reduced motion, forced colors, 200% reflow,
  keyboard validation, focus recovery and named critical controls;
- `F07-A11Y-003`: storage, UTC/Asia-Kolkata boundaries and UTF-8 filenames
  across the configured browser/device matrix.

The post-fix focused command
`npx playwright test e2e/f07-accessibility.spec.ts --reporter=json` observed
72/72 passing cases across Chromium (Asia/Kolkata and UTC), Firefox, WebKit,
Android and iOS. The evidence parser verified only the seven aggregate records
whose exact project-qualified case IDs were present and passed.

## Backend F07 suites

- `F07DatabaseRoleIT` and `F07MigrationBootstrapIT`
- `F07HttpHardeningIT`
- `F07RetentionPrivacyIT` and `RetentionPrivacyServiceTest`
- `F07FeatureFlagObservabilityIT`
- `F07CapacityPerformanceIT`
- focused filter/guard/health/URI/security tests under
  `backend/src/test/java/com/vms/workflow/{infrastructure,security}`

See [TEST_CASES.md](TEST_CASES.md) for the complete 76-case catalog. That
catalog is a requirements contract; a catalog entry is not considered passed
merely because a related class exists.

## Evidence discipline

Record command, exact commit/worktree, environment, duration, result and
checksummed output. Preserve failed runs and append later reruns. Never relabel
intercepted UI tests as provider/full-stack acceptance, and never generate fake
external approvals.

The canonical policy binds every local record to one or more exact
machine-report cases. F07-T030/F07-SUP-003 are backed by a database integration
case that searches success, retry and error responses, captured logs and
persisted security telemetry for the full restricted corpus. E2E-06 and E2E-09
passed the four-case real finance-system lane.

## Preserved execution ledger and current-candidate boundary

The counts below are preserved earlier evidence. They predate V39/V40 and must
not be presented as current-candidate provenance. The current release-control
self-test passes 9/9; current complete frontend/Maven/browser/system,
24-hour-soak, recovery and artifact lanes remain to be executed and bound.

| Lane | Result |
|---|---|
| Static frontend/repository gates | **PASS** — typecheck; lint 0 errors/6 non-blocking Fast Refresh warnings; Vitest 24 files/92 tests; build 3,006 modules with largest chunk 586.90 kB advisory; diff-check. |
| Focused backend pre-final | **PASS** — 73 unit + 45 integration tests. |
| Capacity | **PASS** — 73 unit + 2 capacity tests; dashboard 101ms, check-in p95 404ms, replay p95 69ms, 10k search p95 2ms, 300k report p95 9ms. |
| F07 system | **PASS** — 7/7, covering E2E-01/02/03/04/05/07/10. |
| Finance system | **PASS** — 4/4, covering E2E-06/E2E-09. |
| Migration system | **PASS** — 6/6, covering E2E-08. |
| Complete browser matrix | **PASS** — 274/274, zero failed/skipped/flaky. |
| Complete Maven | **PASS** — definitive R3: 73 unit + 217 integration (290/290), zero failures/errors/skips, BUILD SUCCESS in 03:21. |
| Independent final review | **PASS / CLOSED** — Terra reported no P0–P3 finding. |

The browser history remains additive: 268/274 first run, then 7/7 exact
failed-slice rerun, then 274/274 complete rerun. The migration wait and
multipart filename capture were bounded/corrected in test instrumentation; the
isolated Firefox `_page` error did not reproduce.

Intermediate R2 finished in 39:23 under the corrected 3,600 second watchdog
with two approximately 16–17 minute thread-starvation/clock-leap pauses and
215/217 integration tests passing. Its two delivery-worker failures were
caused by non-dedicated test-database state; the dedicated worker database fix
passes in definitive R3.

Real provider connectivity/certification, production identity, legal/privacy
approval, production deployment/canary, production-like soak/load/DR, manual
accessibility and business/UAT/release approval remain
`ACTION_REQUIRED / NO-GO`. F07-REL-003 still requires the commit-bound
prior/current compatibility rehearsal; no related unit or source-history test
is accepted as an alias.
