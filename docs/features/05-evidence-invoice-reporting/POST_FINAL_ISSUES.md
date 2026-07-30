# F05 — Post-final Issue Register

**Review basis:** current F05 working tree, including V14–V16, Java, React,
Playwright, tests and F05 documentation. The findings below preserve the
original static review; the final execution disposition is recorded separately.

## Post-review remediation status

- `F05-POST-001` is **resolved and locally evidenced**. Independent closure review found separate request and authenticated
  approval contracts/hooks, no production caller-supplied `secondApproverId`,
  exact invoice/rule/readiness/package/policy binding, pending/accepted/expired
  statuses, and a distinct-reviewer action. `E2E-F05-FIN-004B` statically
  verifies requester 409 denial followed by mocked distinct-actor success. The
  documented targeted browser evidence and full **69/69** combined browser
  regression are green.
- `F05-POST-002` is **resolved and locally evidenced**. The integration
  assertion now targets manifest/hash schema v2 and verifies v2
  lineage/disclosure metadata; the focused Finance integration gate is 34/34
  and full backend verification is **154/154 passing**.

## Original independent findings

| ID | Priority | Finding and evidence | Required remediation / acceptance evidence |
| --- | --- | --- | --- |
| F05-POST-001 | **P1** | **The shipped React contract still implements the retired caller-supplied second approver and exposes no way for the distinct authenticated actor to approve.** The backend correctly accepts a second approval only through `POST /procurement/exceptions/{exceptionId}/second-approval` ([FinanceController.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/api/FinanceController.java:402)); it binds the actor, rejects self approval, records authority and transitions the pending exception ([FinanceGovernanceService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/FinanceGovernanceService.java:513)). The UI has neither API client nor hook/form for that route. Instead it sends and labels `secondApproverId` ([procurement-workspace.tsx](/Users/arpan1.mukherjee/code/personal/vms-workflow/src/features/finance/procurement-workspace.tsx:353), [procurement-workspace.tsx](/Users/arpan1.mukherjee/code/personal/vms-workflow/src/features/finance/procurement-workspace.tsx:359)), which the controller DTO no longer defines ([FinanceController.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/api/FinanceController.java:638)). Its type still excludes the actual backend `ACCEPTED` state and lists obsolete `ACTIVE`/`REVOKED` states ([contracts.ts](/Users/arpan1.mukherjee/code/personal/vms-workflow/src/features/finance/contracts.ts:342)). A Procurement user therefore cannot complete a policy-required two-person exception in the product UI, while the misleading input implies it will happen. | Replace the old input with an explicit pending-approval disclosure and an authenticated second-approval control/API contract visible only to an eligible distinct reviewer. Align response/status types with `PENDING_SECOND_APPROVAL`, `ACCEPTED`, and `EXPIRED`. Add a real UI and system-E2E journey proving requester denial, distinct approver success, and expiry/revocation denial. |
| F05-POST-002 | **P1** | **The F05 integration suite contains an assertion that necessarily fails against the current implementation.** Package generation now persists `hash_schema_version = 2` and `render_version = 'manifest-v2'` ([FinancePackageService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/FinancePackageService.java:110), [FinancePackageService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/FinancePackageService.java:156)). `FinanceWorkflowIT.verifiedHandoffDrivesDeterministicPackageAndInvoiceSubmission` still requires version `1` / `manifest-v1` ([FinanceWorkflowIT.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/test/java/com/vms/workflow/integration/FinanceWorkflowIT.java:112)). The count assertion is deterministically zero on the code under review. | Update the test to assert the v2 schema/render contract and full v2 manifest metadata; then run the focused IT and full F05/repository regression suite. Preserve an explicit version-migration assertion rather than weakening the check. |

## Final execution disposition

- The focused backend selection is **34/34 passing** and the full backend is
  **154/154 passing**.
- Frontend typecheck/lint/build pass, Vitest is **88/88**, and combined
  intercepted Playwright is **69/69**.
- The real-system runner ([run-finance-system-e2e.mjs](/Users/arpan1.mukherjee/code/personal/vms-workflow/scripts/run-finance-system-e2e.mjs:1)) uses Spring, Flyway, PostgreSQL and signed test JWTs and is **3/3 passing**. It remains bounded local-system evidence, not a production BFF/OIDC/provider run.
- Cursor tests cover signed/tampered/cross-route/cross-subject behavior.
  Large-fixture SQL-plan/load evidence remains an F07 release-hardening item.

## Security and architecture observations

- The report lifecycle authorization, exact package lineage, exception persistence/expiry and the formerly stale UI contract are now remediated server-side/client-side; the focused local evidence is green, while suite-wide and external acceptance gates remain separate.
- `VITE_E2E_SYSTEM_AUTH` is DEV-only and rejected by public-environment validation in production ([access-token.ts](/Users/arpan1.mukherjee/code/personal/vms-workflow/src/lib/auth/access-token.ts:27), [env.ts](/Users/arpan1.mukherjee/code/personal/vms-workflow/src/lib/env.ts:35)). Keep that boundary and do not make the system-test token bridge generally available.

## Final evidence addendum — 2026-07-30

Exception policy/append-only readiness recovery passed exact Finance **1/1**;
lease recovery was exercised; finance system passed **4/4**. The non-green
Maven 340-test and browser **287/292** aggregates remain preserved, with exact
browser recovery **5/5**. Performance/scale, DR, F07-T057 and G4 are unchanged.
