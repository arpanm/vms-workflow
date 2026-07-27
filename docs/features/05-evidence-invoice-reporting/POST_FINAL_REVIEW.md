# F05 — Post-final Independent Review

**Decision:** **NO-GO for local regression sign-off.** The earlier P0 lineage
and restricted-export defects are locally remediated, but two P1 release
blockers remain: the frontend does not implement the new authenticated
second-approval workflow, and an F05 integration assertion conflicts with the
current manifest version. This was a static review; no Maven or Playwright
command was run.

## Post-review remediation update

Both local P1 source defects identified below have since been remediated.
`F05-POST-001` now has separate React request/approval flows with an exact
binding contract and passing full frontend unit plus seven-case F05 mocked
Playwright evidence for self-approval denial and distinct authenticated
approval. `F05-POST-002` now
asserts the v2 manifest/hash contract. This update does not convert the
historical independent decision to GO: coordinated Maven, full mocked/system
browser regression and a fresh independent review are still required.

## Disposition of `FINAL_ISSUES.md`

| Final issue | Status | Evidence in current tree |
| --- | --- | --- |
| F05-FINAL-001 — restricted export lifecycle access | **Fixed locally; execution pending** | `requireExportAccess` loads the export header and requires route plus stored report permission before status/replay/download ([FinanceGovernanceService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/FinanceGovernanceService.java:1048)); denials are recorded ([FinanceGovernanceService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/FinanceGovernanceService.java:1082)). `FinanceExportAuthorizationIT` covers restricted lifecycle denial and audit facts ([FinanceExportAuthorizationIT.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/test/java/com/vms/workflow/integration/FinanceExportAuthorizationIT.java:92)). |
| F05-FINAL-002 — exact invoice/package lineage | **Fixed locally; execution pending** | Package header persists primary invoice ID/version/document artifact/hash ([FinancePackageService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/FinancePackageService.java:156)); V14 checks primary invoice/document lineage ([V14__finance_evidence_invoice_reporting.sql](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/resources/db/migration/V14__finance_evidence_invoice_reporting.sql:1342)) and readiness package lineage ([V14__finance_evidence_invoice_reporting.sql](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/resources/db/migration/V14__finance_evidence_invoice_reporting.sql:1389)). Submit verifies the current document artifact too ([FinanceInvoiceService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/FinanceInvoiceService.java:877)). The related-note scenario is covered in `FinanceWorkflowIT` ([FinanceWorkflowIT.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/test/java/com/vms/workflow/integration/FinanceWorkflowIT.java:128)). |
| F05-FINAL-003 — exception expiry/two-person approval | **Fixed locally after this review; coordinated execution pending** | Backend has a distinct authenticated approval endpoint and immutable authority evidence ([FinanceGovernanceService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/FinanceGovernanceService.java:513)); on-access expiry reblocks readiness and journals audit/outbox evidence ([FinanceExceptionValidityService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/FinanceExceptionValidityService.java:50)). React now exposes the pending request and exact authenticated approval action, with focused requester-denial/distinct-approval browser evidence; see `F05-POST-001`. |
| F05-FINAL-004 — deterministic XLSX/render bytes | **Fixed locally; execution pending** | Every XLSX ZIP entry has an epoch timestamp ([LocalFinanceReportRenderer.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/LocalFinanceReportRenderer.java:242)); the local adapter test asserts byte equality across metadata-map order and renders ([FinanceLocalAdaptersTest.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/test/java/com/vms/workflow/application/FinanceLocalAdaptersTest.java:114)). |
| F05-FINAL-005 — unsigned/unbounded collection pagination | **Fixed locally; execution pending** | Invoice and package collections issue signed actor/resource/scope-bound cursors after bounded `page-size + 1` queries ([FinanceInvoiceService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/FinanceInvoiceService.java:1834), [FinancePackageService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/FinancePackageService.java:1158)); routes accept cursor parameters directly ([FinanceController.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/api/FinanceController.java:76)). `FinancePaginationIT` supplies route/tamper/actor checks. |
| F05-FINAL-006 — mocked-only browser contract | **Fixed and locally executed: 3/3 passed** | The separate real-system Playwright config/spec and runner provision local JWKS, Spring, Flyway and isolated PostgreSQL ([playwright.system.config.ts](/Users/arpan1.mukherjee/code/personal/vms-workflow/playwright.system.config.ts:1), [finance-system.spec.ts](/Users/arpan1.mukherjee/code/personal/vms-workflow/e2e/finance-system.spec.ts:27), [run-finance-system-e2e.mjs](/Users/arpan1.mukherjee/code/personal/vms-workflow/scripts/run-finance-system-e2e.mjs:1)). The final 2026-07-27 run passed vendor, Procurement/AP/restricted-report, and expiry/revocation/cross-scope journeys against fresh PostgreSQL 18. It also exposed and drove fixes for the month version column, V15 transition guard, and V16 share validity/event aggregation defects. |
| F05-FINAL-007 — incomplete canonical artifact metadata | **Fixed locally; execution pending** | Manifest items now contain safe name, MIME, byte size, object version, classification, retention and availability ([FinancePackageService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/FinancePackageService.java:1018)), which are persisted immutably ([FinancePackageService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/FinancePackageService.java:1078); [V14__finance_evidence_invoice_reporting.sql](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/resources/db/migration/V14__finance_evidence_invoice_reporting.sql:209)). |
| F05-FINAL-008 — snapshot value semantics | **Fixed by explicit live-value policy; execution pending** | API responses label the policy `SNAPSHOT_MEMBERSHIP_VALUES_LIVE_AT_READ` ([FinanceInvoiceService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/FinanceInvoiceService.java:1855)); API documentation states that only membership is frozen and derived values are live ([API_DOCUMENTATION.md](/Users/arpan1.mukherjee/code/personal/vms-workflow/docs/features/05-evidence-invoice-reporting/API_DOCUMENTATION.md:47)). This removes the prior false as-of-value claim. |

## Gate condition

1. Resolve `F05-POST-001` and `F05-POST-002` in
   [POST_FINAL_ISSUES.md](POST_FINAL_ISSUES.md).
2. Execute and retain fresh results for focused/new backend tests, full Maven
   verification, frontend checks, mocked E2E, real-system E2E, and F01–F04
   regression.
3. Re-review the final, stable diff after those results exist. External G4
   approvals (production storage/scanner/renderer, OIDC/grants, retention,
   backup/restore and business/AP acceptance) remain separate production gates.
