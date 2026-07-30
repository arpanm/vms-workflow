# F05 — Final Closure Review

**Verdict:** **GO for local G0–G3 code closure; NO-GO for production release
until performance/scale and external G4 gates pass.** This narrow independent
review found `F05-POST-001` and `F05-POST-002` remediated in the current
source tree. No new actionable source defect was found in their remediation,
the real-system Playwright corrections, or V15–V16. The subsequent focused
Finance integration selection is 34/34 passing and the full local regression
matrix is green.

## Verified closure evidence

| Item | Independent evidence |
| --- | --- |
| F05-POST-001 — two-step exception UX/contract | The client request DTO no longer admits `secondApproverId`, while the separate approval DTO binds invoice, rule, readiness, package and policy ([contracts.ts](/Users/arpan1.mukherjee/code/personal/vms-workflow/src/features/finance/contracts.ts:551)). Separate API calls target the request and authenticated approval endpoints ([api.ts](/Users/arpan1.mukherjee/code/personal/vms-workflow/src/features/finance/api.ts:202)); hooks invalidate the invoice after either result ([hooks.ts](/Users/arpan1.mukherjee/code/personal/vms-workflow/src/features/finance/hooks.ts:273)). The workspace labels the initial action as a request and does not nominate an approver ([procurement-workspace.tsx](/Users/arpan1.mukherjee/code/personal/vms-workflow/src/features/finance/procurement-workspace.tsx:348)); the pending disclosure posts only exact lineage through the current signed-in actor ([procurement-workspace.tsx](/Users/arpan1.mukherjee/code/personal/vms-workflow/src/features/finance/procurement-workspace.tsx:418)). The mocked E2E asserts requester denial, distinct actor acceptance, exact binding and absence of `secondApproverId` ([finance.spec.ts](/Users/arpan1.mukherjee/code/personal/vms-workflow/e2e/finance.spec.ts:203)). Backend integration retains self/cross-tenant/revoked/mismatched denial and distinct authenticated success ([FinanceWorkflowIT.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/test/java/com/vms/workflow/integration/FinanceWorkflowIT.java:683)). |
| F05-POST-002 — manifest test coherence | Package production explicitly emits schema/hash version 2 and `manifest-v2` ([FinancePackageService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/FinancePackageService.java:112)). The former stale integration assertion now checks v2 schema, render version, invoice lineage and disclosures ([FinanceWorkflowIT.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/test/java/com/vms/workflow/integration/FinanceWorkflowIT.java:112)). |
| Real-system Playwright repair | The system test now performs the real query response/close sequence before approving the invoice ([finance-system.spec.ts](/Users/arpan1.mukherjee/code/personal/vms-workflow/e2e/finance-system.spec.ts:181)), matching the application workflow. The runner uses isolated Spring/Flyway/PostgreSQL/JWKS boundaries ([run-finance-system-e2e.mjs](/Users/arpan1.mukherjee/code/personal/vms-workflow/scripts/run-finance-system-e2e.mjs:85)). |
| Finance month schema repair | Finance month list, signed-keyset and workspace reads use the migrated `certification_version` column rather than a nonexistent generic optimistic version; `FinancePaginationIT` ties the public response to the PostgreSQL value. |
| V15 state-machine repair | V15 replaces the existing invoice guard and permits only the needed `CHANGES_REQUESTED → APPROVED_FOR_PROCESSING` continuation while preserving immutable represented fields and all other transition checks ([V15__finance_resolved_query_approval.sql](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/resources/db/migration/V15__finance_resolved_query_approval.sql:4)). |
| V16 share lifecycle/event repair | V16 replaces the expiry-blind partial unique index with non-overlapping PostgreSQL validity windows ([V16__finance_share_validity_windows.sql](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/resources/db/migration/V16__finance_share_validity_windows.sql:1)). Share create/revoke events use the share as aggregate v1/v2, so multiple audited grants do not collide. |

## Real-system execution evidence

- `npm run e2e:finance:system` — **3 passed, 0 failed**.
- Fresh PostgreSQL 18 applied all 22 production/test/seed migrations, including
  V15 and V16.
- Case timings: vendor flow 4.9s; Procurement/AP/restricted authorization
  1.0s; expiry/re-share/revocation/cross-scope denial 6.4s (13.5s total).

## Static checks executed

- `npm run typecheck` — passed.
- `git diff --check` — passed.
- Production code contains no `secondApproverId`; remaining references are
  negative assertions/fixture rejection only.

## Full local quality-gate evidence

- `mvn -B -f backend/pom.xml verify` — **154/154 passed** (11 unit + 143
  integration), including Flyway V14–V16 on Testcontainers PostgreSQL.
- `npm run typecheck`, `npm run lint`, `npm run test`, `npm run build` —
  **passed**; Vitest **88/88**, ESLint 0 errors (6 inherited Fast Refresh
  warnings).
- `npm run e2e` — **69/69 passed** across the combined F00–F05 intercepted
  Chromium regression.
- `npm run e2e:finance:system` — **3/3 passed** through Vite, Spring Security,
  Flyway and isolated PostgreSQL.

## Remaining release gates

- Retain large-fixture/query-plan and load/performance evidence for bounded
  cursor queries as part of F07 release hardening.
- Keep external G4 approvals separate: production object storage/scanner/
  renderer, OIDC and database grants, retention/legal-hold, backup/restore,
  Procurement sign-off, and AP/ERP acceptance.

## Final evidence reconciliation — 2026-07-30

Local exception policy and lease recovery are implemented and exercised. Exact
Finance recovery is **1/1**, finance local-system is **4/4**, and accessibility
is **3/3** intercepted-browser evidence. The preserved aggregates remain Maven
340 executed with 2 failures and 1 error and browser **287/292**, followed by
browser exact recovery **5/5**; neither aggregate is relabeled green. The
verdict continues to exclude performance/scale, controlled DR, F07-T057 and G4.
