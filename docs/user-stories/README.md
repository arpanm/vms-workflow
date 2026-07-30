# Persona User-Story Catalog

This is the authoritative decomposition of the five persona narratives into
45 independently traceable user stories. Each linked document owns its
description, UI flow, acceptance criteria, automation and current status.

Status meanings:

- `IMPLEMENTED`: backend/database and UI flow exist locally.
- `AUTOMATED`: named unit/integration/browser coverage exists.
- `VERIFIED`: the latest consolidated execution passed.
- `EXTERNAL`: production provider or accountable-human evidence remains
  outside the repository; it does not reduce the implemented local flow.

## Platform administrator

| ID | User story | Implementation | Testing |
|---|---|---|---|
| ADM-001 | [Onboard a client and delivery scope](ADM-001-onboard-client.md) | IMPLEMENTED — V46/API/UI | VERIFIED — PostgreSQL + browser regression |
| ADM-002 | [Add multiple client users](ADM-002-client-users.md) | IMPLEMENTED — V46/API/UI | VERIFIED — PostgreSQL + browser regression |
| ADM-003 | [Grant scoped action permissions](ADM-003-permission-grants.md) | IMPLEMENTED — RBAC/API/UI | VERIFIED — PostgreSQL + browser regression |
| ADM-004 | [Audit onboarding and permission changes](ADM-004-onboarding-audit.md) | IMPLEMENTED — immutable audit | VERIFIED — PostgreSQL integration |

## Client user

| ID | User story | Implementation | Testing |
|---|---|---|---|
| CLI-001 | [Create next-month tasks](CLI-001-create-next-month-task.md) | IMPLEMENTED — V46 workspace | VERIFIED — PostgreSQL + browser regression |
| CLI-002 | [Update task definition and acceptance criteria](CLI-002-update-task.md) | IMPLEMENTED — optimistic API | VERIFIED — PostgreSQL integration |
| CLI-003 | [Attach PRD, design and tracker links](CLI-003-task-links.md) | IMPLEMENTED — typed HTTPS links | VERIFIED — PostgreSQL + browser regression |
| CLI-004 | [Comment and tag participants](CLI-004-comments-mentions.md) | IMPLEMENTED — scoped mentions | VERIFIED — PostgreSQL + browser regression |
| CLI-005 | [Browse backlog, future and delivered tasks](CLI-005-task-timelines.md) | IMPLEMENTED — timeline filters | VERIFIED — PostgreSQL + browser regression |
| CLI-006 | [See tasks where I am tagged](CLI-006-mention-inbox.md) | IMPLEMENTED — personal filter | VERIFIED — PostgreSQL integration |
| CLI-007 | [L1 approve next-month assignments and stack rank](CLI-007-plan-l1-stack-rank.md) | IMPLEMENTED — version-bound approval | VERIFIED — PostgreSQL integration |
| CLI-008 | [See end-of-month delivery status](CLI-008-delivery-visibility.md) | IMPLEMENTED — task/certification UI | VERIFIED — PostgreSQL + browser regression |
| CLI-009 | [L1 approve delivery status](CLI-009-delivery-l1.md) | IMPLEMENTED — V46 approval | VERIFIED — PostgreSQL integration |
| CLI-010 | [L2 approve delivery status](CLI-010-delivery-l2.md) | IMPLEMENTED — V46 approval | VERIFIED — PostgreSQL integration |
| CLI-011 | [View and approve invoices](CLI-011-invoice-review.md) | IMPLEMENTED — F05 finance/confirmation | VERIFIED by existing finance suites |

## ArrowFoundry administrator

| ID | User story | Implementation | Testing |
|---|---|---|---|
| AFA-001 | [Browse clients and client dashboards](AFA-001-client-dashboards.md) | IMPLEMENTED — scope/dashboard | VERIFIED by existing core suites |
| AFA-002 | [See task assignment and approval overview](AFA-002-task-overview.md) | IMPLEMENTED — V46 workspace | VERIFIED — PostgreSQL + browser regression |
| AFA-003 | [Assign multiple practitioners and disciplines](AFA-003-multi-role-assignment.md) | IMPLEMENTED — V46 assignments | VERIFIED — PostgreSQL integration |
| AFA-004 | [Update and monitor delivery status](AFA-004-delivery-status.md) | IMPLEMENTED — V46/certification | VERIFIED — PostgreSQL + browser regression |
| AFA-005 | [Upload attendance data](AFA-005-attendance-upload.md) | IMPLEMENTED — F02/F06 imports | VERIFIED by existing workforce/migration suites |
| AFA-006 | [Upload an invoice](AFA-006-invoice-upload.md) | IMPLEMENTED — F05 private artifacts | VERIFIED by finance suites |
| AFA-007 | [Submit invoice with governed monthly evidence](AFA-007-submit-invoice.md) | IMPLEMENTED — F05 package/readiness | VERIFIED by finance suites |
| AFA-008 | [Create tasks on behalf of a client](AFA-008-client-proxy-task.md) | IMPLEMENTED — recorded proxy flag | VERIFIED — PostgreSQL integration |
| AFA-009 | [Bulk create client tasks](AFA-009-bulk-task-upload.md) | IMPLEMENTED — atomic 500-row API | VERIFIED — PostgreSQL integration |
| AFA-010 | [Browse past tasks and invoices](AFA-010-past-history.md) | IMPLEMENTED — timeline/finance history | VERIFIED — consolidated regression |
| AFA-011 | [See attendance, leave balances and requests](AFA-011-workforce-overview.md) | IMPLEMENTED — F02 administration | VERIFIED by workforce suites |
| AFA-012 | [Approve or reject leave](AFA-012-leave-approval.md) | IMPLEMENTED — F02 governed decisions | VERIFIED by workforce suites |
| AFA-013 | [Manage holidays and employee leave setup](AFA-013-holiday-user-setup.md) | IMPLEMENTED — F02 administration | VERIFIED by workforce suites |
| AFA-014 | [Bulk import historical attendance](AFA-014-historical-attendance.md) | IMPLEMENTED — F06 migration | VERIFIED by migration suites |

## ArrowFoundry practitioner

| ID | User story | Implementation | Testing |
|---|---|---|---|
| AFU-001 | [Browse clients and my assigned tasks](AFU-001-my-client-tasks.md) | IMPLEMENTED — scoped personal filter | VERIFIED — PostgreSQL integration |
| AFU-002 | [Update my task delivery status](AFU-002-update-my-status.md) | IMPLEMENTED — assignee guard | VERIFIED — PostgreSQL + browser regression |
| AFU-003 | [Transfer, add an assignee or claim a task](AFU-003-transfer-claim.md) | IMPLEMENTED — guarded assignment | VERIFIED — PostgreSQL integration |
| AFU-004 | [Comment and tag client/vendor users](AFU-004-comments-mentions.md) | IMPLEMENTED — engagement participant guard | VERIFIED — PostgreSQL + browser regression |
| AFU-005 | [See tasks where I am tagged](AFU-005-mention-inbox.md) | IMPLEMENTED — personal filter | VERIFIED — PostgreSQL integration |
| AFU-006 | [Record multiple estimates and totals](AFU-006-estimates.md) | IMPLEMENTED — per-user ledger | VERIFIED — PostgreSQL integration |
| AFU-007 | [Delete a prior estimate with authority](AFU-007-delete-estimate.md) | IMPLEMENTED — soft delete/audit | VERIFIED — PostgreSQL integration |
| AFU-008 | [Record actual effort and totals](AFU-008-actual-effort.md) | IMPLEMENTED — dated ledger | VERIFIED — PostgreSQL integration |
| AFU-009 | [Attach design, code and test evidence](AFU-009-evidence-links.md) | IMPLEMENTED — typed HTTPS links | VERIFIED — PostgreSQL + browser regression |
| AFU-010 | [Check in and check out](AFU-010-attendance.md) | IMPLEMENTED — F02 self-service | VERIFIED by workforce suites |
| AFU-011 | [Apply for leave and see balance/holidays](AFU-011-leave-self-service.md) | IMPLEMENTED — F02 self-service | VERIFIED by workforce suites |
| AFU-012 | [Browse past, current and future client tasks](AFU-012-client-task-history.md) | IMPLEMENTED — timeline filters | VERIFIED — PostgreSQL + browser regression |

## Client procurement user

| ID | User story | Implementation | Testing |
|---|---|---|---|
| PRC-001 | [See pending invoice submissions](PRC-001-pending-invoices.md) | IMPLEMENTED — F05 control tower | VERIFIED by finance suites |
| PRC-002 | [Approve or reject invoices](PRC-002-invoice-decision.md) | IMPLEMENTED — versioned review | VERIFIED by finance suites |
| PRC-003 | [View and download invoice evidence](PRC-003-invoice-evidence.md) | IMPLEMENTED — private package downloads | VERIFIED by finance suites |
| PRC-004 | [Browse past invoices](PRC-004-past-invoices.md) | IMPLEMENTED — cursor history | VERIFIED by finance suites |

## Shared implementation and test evidence

- V46 migration: `backend/src/main/resources/db/migration/V46__client_onboarding_and_work_item_collaboration.sql`
- Backend API: `ClientCollaborationController` and
  `ClientCollaborationService`
- UI: `/work-items` and `/administration/clients`
- Existing governed flows: F02 workforce, F03 planning, F04 certification and
  F05 finance/procurement.
- Consolidated execution: [TEST_EXECUTION.md](TEST_EXECUTION.md)
