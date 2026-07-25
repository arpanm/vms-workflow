# 03 — Identity, RBAC, Approval Matrix and Administration

**Version:** 1.0
**Status:** Build specification
**Related:** 02, 09, 13, 14, 17

---

## 1. Objective

Replace the prototype's client-side role selector and anonymous data access with production-grade identities, scoped permissions, delegation and configurable approval/contact governance.

---

## 2. Authentication model

### 2.1 Supported modes

1. **Enterprise SSO/OIDC/SAML** for Reliance and, where available, ArrowFoundry.
2. **Supabase Auth invite/passwordless or OTP** for authorized external users when federation is unavailable.
3. **Service identities** for scheduled jobs and integrations; never interactive login.
4. **Demo mode** only when `VITE_DEMO_MODE=true` in a non-production environment.

### 2.2 Identity fields

- immutable platform user ID;
- verified email(s), identity provider and provider subject ID;
- display name and status;
- organization memberships;
- last login and MFA/assurance metadata where supplied;
- no business permission stored only in JWT user-editable metadata;
- account disable/lock and session revocation timestamps.

### 2.3 Invite workflow

1. Authorized admin enters email, organization, role, scope and expiry.
2. System rejects duplicates/conflicting active memberships and records inviter.
3. Invitee verifies identity using approved provider.
4. Membership becomes active only after email/IdP verification and, for privileged roles, optional second approver.
5. Expired/revoked invitations cannot be reused.

---

## 3. Authorization model

Authorization is the intersection of:

`authenticated identity + active organization membership + role/permission + resource scope + effective date + object state + separation-of-duties rule`.

All checks are enforced in server actions/functions and PostgreSQL RLS. UI controls mirror, but never replace, server authorization.

### 3.1 Scope hierarchy

- `PLATFORM`
- `ORGANIZATION`
- `ENGAGEMENT`
- `PROJECT`
- `SELF` (employee's own attendance/leave)
- `OBJECT_ASSIGNMENT` (assigned product owner/approver/reviewer)

A broader role does not automatically grant a narrower organization's confidential data unless explicitly configured.

---

## 4. Canonical roles

| Role | Typical organization | Purpose |
|---|---|---|
| `PLATFORM_ADMIN` | Platform operator | Technical tenant/configuration administration; cannot approve business evidence by default |
| `ORG_ADMIN` | Vendor/client | Manage users and organization configuration within scope |
| `ENGAGEMENT_ADMIN` | Client/vendor | Manage engagement, projects, calendars, contacts and policy assignments |
| `VENDOR_HR_ADMIN` | ArrowFoundry | Employee, allocation, leave balance and attendance administration |
| `VENDOR_MANAGER` | ArrowFoundry | Review team attendance/leave, submit delivery evidence |
| `EMPLOYEE` | ArrowFoundry | Self-service attendance, leave, regularization and assignment view |
| `CLIENT_PRODUCT_OWNER` | Reliance | Create/own deliverables, review execution, certify assigned work |
| `CLIENT_APPROVER` | Reliance | Approve monthly plan/certification/confirmation as policy permits |
| `PROGRAM_GOVERNANCE` | Reliance | Monitor month, manage exceptions, request/review reopen |
| `PROCUREMENT_REVIEWER` | Central Procurement | Read package, review invoice readiness, accept documented exception |
| `FINANCE_AP` | Reliance/Procurement | Track invoice/payment workflow; no employee salary data exists |
| `INTEGRATION_ADMIN` | Authorized org | Configure/test credentials and replay integration failures |
| `AUDITOR_READONLY` | Authorized | Read evidence/audit with export permission; no mutation |
| `SUPPORT_OPERATOR` | Platform | Troubleshoot within approved support boundary; privileged access audited |
| `SERVICE_ACCOUNT` | Platform | Non-human background/integration actions with minimal permissions |

Roles are templates. Permissions and scope assignments are stored separately so future roles can be introduced without code branches.

---

## 5. Permission catalog

Examples of atomic permissions:

- `organization.read/update`
- `membership.invite/read/update/deactivate`
- `engagement.read/update/configure`
- `project.create/update/archive`
- `employee.create/read/update/disable/archive/import`
- `allocation.create/update/end/import`
- `calendar.manage`, `holiday.manage`
- `leave.self.apply/cancel`, `leave.team.review`, `leave.balance.adjust`
- `attendance.self.checkin/checkout/read`
- `attendance.team.review`, `attendance.admin.correct`, `attendance.close/reopen/import`
- `regularization.self.apply`, `regularization.review`
- `deliverable.plan.create/update/submit/approve/reopen`
- `deliverable.delivery.submit/certify`
- `confirmation.request/respond/record_manual/void`
- `invoice.upload/submit/review/update_payment_status`
- `evidence.generate/read/export/void`
- `integration.configure/read_health/replay`
- `audit.read/export`
- `month.transition/reopen/close`

Every endpoint/mutation declares one or more required permissions and checks the resource scope.

---

## 6. Default permission matrix

| Capability | Employee | Vendor HR/Admin | Vendor Manager | Product Owner | Client Approver/Gov | Procurement | Finance | Auditor |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| View own attendance/leave | ✓ | ✓ | ✓ | — | scoped summary | summary | — | scoped |
| Check in/out | ✓ | optional | — | — | — | — | — | — |
| Manage employee master/balances | — | ✓ | read | — | read roster | read package | — | read |
| Review regularization/leave | — | ✓ | ✓ if assigned | — | exception view | — | — | read |
| Create monthly plan/deliverables | — | comment | comment | ✓ | ✓ | read | — | read |
| Approve/freeze plan | — | — | — | if policy | ✓ | optional observer | — | read |
| Submit delivery | — | — | ✓ | — | — | — | — | read |
| Certify delivery | — | — | — | ✓ assigned | ✓ | read | — | read |
| Confirm consolidated month | — | — | — | if authorized | ✓ | observer/exception | — | read |
| Upload invoice | — | authorized vendor | ✓ if granted | — | read | read/review | read | read |
| Accept procurement exception | — | — | — | — | — | ✓ | — | read |
| Reopen closed month | — | request | request | request | approve | approve if policy | — | read |
| Configure integrations | — | optional | — | — | — | — | — | read health only |

Actual permissions come from policy records; this table is the initial seed.

---

## 7. Separation of duties

Mandatory default controls:

- A vendor cannot certify its own delivery on behalf of the client.
- A person who created an admin attendance correction cannot be its second approver where dual control is configured.
- A package generator/service account cannot record business confirmation.
- A product owner may create and approve a plan only if the approval policy explicitly permits self-approval; default is prohibited for material baselines.
- A user cannot accept a Procurement exception unless assigned the Procurement role for that engagement.
- Platform administrators have no implicit business-approval authority.
- Manual email confirmation recording requires a second authorized reviewer unless the source was automatically verified.

---

## 8. Approval policies

### 8.1 Policy fields

- policy ID/name/version;
- business object/action (`PLAN_APPROVAL`, `LEAVE_APPROVAL`, `REGULARIZATION`, `ATTENDANCE_CORRECTION`, `DELIVERY_CERTIFICATION`, `MONTH_CONFIRMATION`, `REOPEN`, `PROCUREMENT_EXCEPTION`);
- organization/engagement/project scope;
- ordered stages;
- stage role/group/explicit assignee;
- quorum and substitute/delegate behavior;
- due duration/calendar;
- escalation path;
- self-approval restriction;
- evidence/comment requirements;
- effective dates.

### 8.2 Decision states

`PENDING`, `APPROVED`, `REJECTED`, `CHANGES_REQUESTED`, `CANCELLED`, `EXPIRED`, `SUPERSEDED`.

An expired approval is not an approval. A replacement workflow must be created or an authorized exception recorded.

### 8.3 Approval action record

Capture:

- action and decision;
- actor identity, organization, role and authority snapshot;
- acted timestamp and actor timezone;
- comments/reason code;
- object/version/hash approved;
- delegated-from identity where relevant;
- source (`IN_APP`, `SECURE_EMAIL_LINK`, `VERIFIED_EMAIL_REPLY`, `HISTORICAL_IMPORT`, `MANUAL_EVIDENCE`);
- IP/device/session metadata subject to privacy policy;
- attachment/evidence refs.

---

## 9. Delegation and absence

1. A role-holder can create time-bounded delegation to another eligible user.
2. Delegation must specify scope, effective dates and actions.
3. Privileged delegation may require approval.
4. The system routes new tasks to the delegate during the period; existing tasks can be reassigned by policy.
5. Action records show both actor and original authority holder/delegation chain.
6. Delegation never grants more scope than the delegator.
7. Emergency assignment by admin requires reason and enhanced audit alert.

---

## 10. Contact and distribution groups

Configurable groups per engagement/project:

- client product owners;
- client approvers;
- vendor delivery/HR/finance contacts;
- Central Procurement CC;
- escalation level 1/2/3;
- audit observers.

Group versions are snapshotted when a message is generated. Later membership changes do not rewrite historical recipients.

Validation:

- at least one active product owner and vendor delivery owner;
- Procurement CC mandatory for commitment and consolidated confirmation emails in this engagement;
- no invalid/unverified address in an approval role;
- duplicate recipients deduplicated while retaining role attribution.

---

## 11. Administration screens

### 11.1 Users and access

- Search/filter by organization, role, status and scope.
- Invite, resend, revoke, deactivate and view access history.
- Effective-dated role assignments.
- “View as” is forbidden in production; support impersonation, if enabled, requires explicit banner, user consent/policy and audit.

### 11.2 Approval and delegation designer

- Stage builder with preview of effective approvers.
- Validation for missing approvers, circular escalation and impossible quorum.
- Simulation: select object/scope/date and show routing result before activation.
- Publish creates a new version; old months retain old policy.

### 11.3 Contact groups

- Effective-dated membership, validation and test email.
- Preview recipients for each email template/event.

### 11.4 Security/access review

- Dormant accounts, privileged roles, expiring delegations and failed login summary.
- Quarterly access-review export and attestation.

---

## 12. Notifications

- Invite accepted/revoked.
- Role granted/changed/removed.
- Privileged role awaiting second approval.
- Delegation created/expiring/ended.
- Approval assignment, reminder, escalation and decision.
- Failed authorization attempts above threshold alert security/admin.

---

## 13. Acceptance criteria and tests

### Identity/RBAC

- Given an ArrowFoundry employee, direct query attempts for another employee's attendance are blocked by RLS.
- Given a Product Owner scoped to Project A, Project B's deliverables are not returned.
- Given a disabled user with a still-valid browser session, the next protected request is denied and sessions can be revoked.
- Given a local UI role selection attempt in production, authorization remains unchanged.

### Approval/delegation

- Given an approval policy requiring two of three client approvers, one approval is insufficient and two distinct eligible approvals complete it.
- Given an approver on active delegation, the delegate can act and the action shows the delegation chain.
- Given an expired delegation, the delegate is denied.
- Given a vendor user, certification mutation on behalf of client is denied even if the UI request is forged.
- Given a service account, business confirmation mutation is denied.

### Administration

- Publishing a new policy version does not alter July's routing if effective from August.
- Deactivating a user preserves historical approval display and audit identity.
- Contact group preview identifies missing Procurement CC before a commitment email can be sent.
