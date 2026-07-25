# 02 — Canonical Domain Model: Organizations, Engagements, Projects and Months

**Version:** 1.0
**Status:** Build specification
**Related:** 01, 03, 13, 20

---

## 1. Objective

Define a stable, non-hard-coded business model that supports ArrowFoundry/Reliance now and additional vendors, clients, procurement bodies, teams, projects and monthly evidence cycles later.

---

## 2. Canonical entity hierarchy

```text
Organization
 ├─ OrganizationMembership -> UserIdentity + RoleAssignments
 ├─ ContactGroup
 ├─ WorkingCalendar / HolidayCalendar
 └─ IntegrationConnection

Engagement (client organization + vendor organization + optional procurement organization)
 ├─ Project(s)
 ├─ EngagementRoleAssignment(s)
 ├─ ApprovalPolicy / NotificationPolicy / AttendancePolicy
 ├─ EmployeeEngagementAssignment(s)
 └─ EngagementMonth (one per calendar month)
      ├─ RosterSnapshot
      ├─ AttendanceSnapshot
      ├─ MonthlyDeliverablePlan -> PlanVersion -> Deliverables
      ├─ DeliverySubmission -> Certification
      ├─ BusinessConfirmation
      ├─ EvidencePackageVersion
      └─ Invoice
```

---

## 3. Organization

### 3.1 Fields

| Field | Type | Required | Rules |
|---|---|---:|---|
| `id` | UUID | yes | immutable |
| `legal_name` | text | yes | unique with country/identifier where available |
| `display_name` | text | yes | user-facing |
| `organization_type` | enum | yes | `CLIENT`, `VENDOR`, `PROCUREMENT`, `PLATFORM_OPERATOR`, `OTHER` |
| `external_identifier` | text | no | CIN/vendor code/etc.; encrypted or restricted if sensitive |
| `primary_domain` | text | no | used for invite/confirmation verification; not sufficient alone for access |
| `status` | enum | yes | `ACTIVE`, `INACTIVE`, `ARCHIVED` |
| `default_timezone` | IANA string | yes | initial `Asia/Kolkata` |
| `default_locale` | string | yes | initial `en-IN` |
| `created_at/by`, `updated_at/by` | audit | yes | server generated |

### 3.2 Seed configuration

Seed, do not hard-code:

- ArrowFoundry — `VENDOR`.
- Reliance Intelligence — `CLIENT`.
- Reliance/Jio Central Procurement — `PROCUREMENT` or a contact group under an existing legal organization, based on tenant decision.

---

## 4. Engagement

### 4.1 Fields

| Field | Required | Notes |
|---|---:|---|
| `engagement_code`, `name` | yes | unique code; display name |
| `client_organization_id` | yes | Reliance Intelligence initially |
| `vendor_organization_id` | yes | ArrowFoundry initially |
| `procurement_organization_id` | no | can instead use contact group |
| `engagement_model` | yes | `DEDICATED_RESOURCE_MONTHLY`, `FIXED_COST_DELIVERY`, `STAFF_AUGMENTATION`, `HYBRID` |
| `start_date`, `end_date` | yes/no | initial start date `2026-06-01`; end nullable |
| `status` | yes | `DRAFT`, `ACTIVE`, `SUSPENDED`, `COMPLETED`, `ARCHIVED` |
| `billing_cycle` | yes | `CALENDAR_MONTH` for this use case |
| `attendance_required` | yes | true initially |
| `deliverable_baseline_required` | yes | true |
| `delivery_certification_required` | yes | true |
| `business_confirmation_required` | yes | true |
| `commercial_data_policy` | yes | `NO_RATE_OR_SALARY_STORAGE` |
| `default_project_id` | no | convenience only; allocations still explicit |
| `configuration_version_id` | yes | points to immutable effective-dated configuration |

### 4.2 Engagement configuration version

Configuration that can affect evidence must be effective-dated and versioned:

- attendance source and thresholds;
- leave policy association;
- planning/certification deadlines;
- required approvers and quorums;
- notification groups and escalation schedule;
- mandatory package contents;
- time zone;
- permitted retroactivity/reopen policy.

Changes apply prospectively unless an authorized user explicitly reopens/recomputes affected months.

---

## 5. Project and team

### 5.1 Project fields

| Field | Required | Rules |
|---|---:|---|
| `engagement_id` | yes | tenant boundary inherited |
| `project_code`, `name` | yes | initial examples `NAM`, `AGENTIC_SHOPOS` |
| `description` | no | business scope |
| `parent_project_id` | no | permits program/project hierarchy |
| `client_product_owner_group_id` | yes | at least one active owner |
| `vendor_delivery_owner_group_id` | yes | at least one active owner |
| `start_date`, `end_date`, `status` | yes/no | effective-dated |
| `linear_team_id/project_id` | no | optional default mapping, not a credential |

### 5.2 Team

A team is a reporting/allocation grouping within a project. It does not replace organization membership or authorization. Fields include name, manager, active dates, project, and optional Linear team mapping.

---

## 6. Engagement month

### 6.1 Key and uniqueness

One record per `(engagement_id, month_start_date)`. `month_start_date` must be the first day of a calendar month in the engagement timezone.

### 6.2 State machine

```text
HISTORICAL_DRAFT ─┐
DRAFT ─> PLANNING ─> PLAN_PENDING_APPROVAL ─> PLAN_APPROVED ─> ACTIVE
                                                          └─> DELIVERY_SUBMITTED
                                                              └─> DELIVERY_REVIEW
                                                                  └─> CONFIRMATION_PENDING
                                                                      └─> CONFIRMED
                                                                          └─> INVOICE_READY
                                                                              └─> INVOICE_SUBMITTED
                                                                                  └─> CLOSED

Any post-approval correction requiring evidence change:
CLOSED/CONFIRMED/... ─> REOPEN_REQUESTED ─> REOPENED ─> applicable earlier state ─> new versions

Historical path may use:
HISTORICAL_DRAFT -> HISTORICAL_REVIEW -> HISTORICAL_PENDING_CONFIRMATION -> CONFIRMED
```

### 6.3 State rules

| Transition | Preconditions | Authority |
|---|---|---|
| `DRAFT → PLANNING` | active engagement/configuration | governance admin/product owner |
| `PLANNING → PLAN_PENDING_APPROVAL` | plan completeness passes | plan submitter |
| `→ PLAN_APPROVED` | approval policy quorum met | authorized Reliance approver(s) |
| `→ ACTIVE` | month start reached or authorized early activation | system/governance |
| `→ DELIVERY_SUBMITTED` | vendor status/evidence for every baseline deliverable | vendor delivery owner |
| `→ DELIVERY_REVIEW` | submission validated | system/product owner |
| `→ CONFIRMATION_PENDING` | certification complete and attendance snapshot ready | system/governance |
| `→ CONFIRMED` | verified business confirmation captured | authorized confirmer/system capture |
| `→ INVOICE_READY` | readiness policy passes | system |
| `→ INVOICE_SUBMITTED` | invoice submitted and package linked | vendor finance user |
| `→ CLOSED` | configured procurement/finance conditions met | governance/procurement |
| `→ REOPEN_REQUESTED` | reason, impacted objects and requested state supplied | authorized user |
| `→ REOPENED` | reopen approval policy met | governance/procurement authority |

Invalid transitions return a typed error and are audit logged.

### 6.4 Month metadata

- `planning_due_at`, `plan_approval_due_at`.
- `delivery_submission_due_at`, `certification_due_at`, `confirmation_due_at`.
- `attendance_close_due_at`, `invoice_due_at`.
- `current_plan_version_id`, `current_attendance_snapshot_id`, `current_confirmation_id`, `current_evidence_package_id`.
- `risk_status`: `ON_TRACK`, `AT_RISK`, `BREACHED`, `BLOCKED`, `EXCEPTION_ACCEPTED`.
- `historical_flag`, `represented_period`, `data_provenance_summary`.

---

## 7. Effective dating and non-destructive history

Entities affecting evidence use:

- `valid_from` (inclusive), `valid_to` (exclusive/null);
- `recorded_at` (when the system learned it);
- `recorded_by` and `source`;
- `supersedes_id` or version number;
- no destructive delete after use in a month.

Examples:

- an employee moves projects on 15 July: two allocations, not overwritten project ID;
- a product owner changes on 1 August: July certification retains the old owner's authority snapshot;
- a holiday is added after attendance close: it does not rewrite July until authorized reopen/recompute.

---

## 8. Snapshot model

Every snapshot contains:

- snapshot ID/type/version;
- engagement/month;
- configuration/policy version IDs;
- source record IDs and versions;
- canonical JSON payload or normalized child rows;
- generated timestamp/by service identity;
- SHA-256 manifest checksum;
- superseded/superseding snapshot links;
- reason for regeneration;
- immutable storage location where applicable.

Mandatory snapshots:

1. approved roster/allocation;
2. plan baseline and linked Linear plan-time state;
3. closed attendance register;
4. month-end Linear state;
5. delivery certification;
6. verified confirmation;
7. evidence package manifest.

---

## 9. Approval policy abstraction

Approval requirements are data-driven:

- business object type;
- scope (organization/engagement/project);
- ordered or parallel stages;
- role/group/explicit assignee;
- quorum (`ANY_ONE`, `ALL`, `N_OF_M`);
- monetary thresholds are available for other modules but not used for salary/rate calculations here;
- deadline, reminders, escalation and delegation;
- whether self-approval is prohibited;
- whether procurement review is mandatory;
- effective dates/version.

A person's display name must never be embedded as business logic.

---

## 10. Canonical invariants

- An engagement's client and vendor cannot be the same organization.
- A project belongs to exactly one engagement.
- An engagement month cannot exist outside the engagement active period unless marked historical exception.
- Only one current approved plan version, attendance snapshot, confirmation and evidence package exists per engagement month; all prior versions remain.
- Closing a month locks all included records through database and service-layer controls.
- Reopening creates a new closure lineage; it never mutates the original evidence package.
- No evidence package may combine data from different engagement months unless explicitly generated as a portfolio report, which is not invoice evidence.
- All timestamps in evidence include timezone context.

---

## 11. Acceptance criteria

- Given two engagements with different client/vendor pairs, users see only the scopes granted to their memberships.
- Given an engagement configuration change effective August, July evidence continues to use July's configuration version.
- Given a closed July month, editing an included deliverable or attendance day is blocked until authorized reopen.
- Given a reopened month, the original package remains downloadable and a new package version is linked as superseding it.
- Given an attempted invalid month transition, the database/service rejects it and writes an audit/security event.
- Given a historical June record entered in July, the system displays both represented date and recorded timestamp.
