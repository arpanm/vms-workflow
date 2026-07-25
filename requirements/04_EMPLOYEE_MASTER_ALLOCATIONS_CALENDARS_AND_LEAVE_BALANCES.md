# 04 — Employee Master, Allocations, Calendars and Leave Balances

**Version:** 1.0
**Status:** Build specification
**Related:** 03, 05, 06, 11, 13, 18

---

## 1. Objective

Maintain an effective-dated, auditable roster of vendor resources and the policies needed to determine which days each employee was expected to work, where they were allocated and what leave entitlement was available.

---

## 2. Employee master

### 2.1 Required fields

| Field | Required | Validation/use |
|---|---:|---|
| `employee_id` | yes | UUID internal key |
| `organization_id` | yes | vendor organization |
| `employee_number` | yes | unique within organization; primary greytHR mapping candidate |
| `first_name`, `last_name`, `display_name` | yes | preserve legal/display distinction if needed |
| `work_email` | yes | unique active identity mapping unless shared-email exception |
| `personal_email`, `mobile` | no | restricted visibility; not in procurement package by default |
| `employment_status` | yes | `PREBOARDING`, `ACTIVE`, `ON_LEAVE`, `SUSPENDED`, `EXITED`, `ARCHIVED` |
| `join_date` | yes | cannot be after exit date |
| `exit_date` | no | effective last service date |
| `designation`, `skill_category`, `grade` | no | no salary/rate data |
| `manager_employee_id` | no | same org; circular reporting blocked |
| `default_timezone` | yes | inherited unless overridden |
| `working_calendar_id` | yes | effective-dated assignment |
| `attendance_policy_id` | yes | effective-dated assignment |
| `leave_policy_id` | yes | effective-dated assignment |
| `attendance_source_mode` | yes | inherited/override; see PRD 06 |
| `greythr_employee_ref` | no | external key; restricted admin field |
| `activation_status` | yes | `ENABLED`, `DISABLED`; disabled cannot check in/login |
| audit/effective fields | yes | source, valid dates, recorded time/by |

### 2.2 Optional procurement-visible roster fields

- employee number;
- name;
- designation/role;
- project/team allocation;
- allocation dates;
- employment active dates;
- attendance summary.

Personal contact data, identity documents and salary data are excluded.

### 2.3 Lifecycle rules

- `DISABLED` blocks login/check-in but does not end employment or allocation automatically.
- `EXITED` requires exit date and automatically prevents new attendance after the effective date, except authorized historical correction.
- Archiving is allowed only after exit/inactivation and never deletes history.
- Employee number changes create alias/mapping history; they do not break imported records.
- A user identity may be linked to one active employee record per organization unless a documented migration exception exists.
- Duplicate detection uses employee number, work email, greytHR ref and configurable fuzzy name/date checks.

---

## 3. Employee assignment and allocation

### 3.1 Engagement assignment

Required before an employee can appear in an engagement roster:

- employee;
- engagement;
- valid from/to;
- deployment status (`PLANNED`, `ACTIVE`, `TEMPORARILY_INACTIVE`, `ENDED`);
- primary project;
- allocation percentage or capacity units;
- approved by/approval record if policy requires;
- source/provenance.

### 3.2 Project allocation

An employee may have multiple concurrent allocations.

| Field | Rule |
|---|---|
| `project_id` | must belong to engagement |
| `valid_from/to` | overlap allowed across projects only |
| `allocation_percent` | >0 and ≤100 per row |
| total allocation | ≤100% for same date range by default; admin override requires reason |
| `role_on_project` | text/catalog; not authorization role |
| `team_id` | optional |
| `billable_indicator` | optional evidence classification; no rate |
| `allocation_owner` | vendor/client owner as configured |

Use temporal validation to prevent accidental over-allocation. Split date ranges automatically when an allocation changes mid-month; never overwrite prior days.

### 3.3 Deliverable assignment

Many-to-many relation between employee and monthly deliverable with:

- contribution role (`OWNER`, `CONTRIBUTOR`, `REVIEWER`, `SUPPORT`);
- expected allocation/effort indicator optional and non-commercial;
- assignment dates;
- source (`MANUAL`, `LINEAR_ASSIGNEE_SUGGESTION`, `IMPORT`);
- confirmation status.

Linear assignee data may suggest, but must not silently create, employment allocations.

### 3.4 Roster snapshot

At plan approval or month start, capture:

- employees active in engagement during any portion of month;
- day-level effective assignment/allocation ranges;
- project/team/role;
- join/exit dates;
- calendar/policy/source versions;
- exceptions such as late joiner, exit or temporary inactive period.

Later changes require a roster revision and impact review.

---

## 4. Working calendars

### 4.1 Calendar hierarchy

Expected-day resolution applies in this precedence order:

1. authorized employee-date override;
2. project/site exceptional working/non-working date;
3. employee-specific weekly pattern assignment;
4. organization/engagement holiday list;
5. base working calendar weekly pattern.

A higher-priority rule records which lower rule it overrode.

### 4.2 Calendar fields

- name, timezone, effective dates and version;
- weekday classification for Monday–Sunday (`WORKING`, `WEEKLY_OFF`, optional `HALF_DAY_EXPECTED`);
- standard start/end window for reminder/display only;
- expected net work minutes (default 540 for a full day);
- half-day expected minutes (default 270 unless configured);
- unpaid break policy;
- grace/rounding rules;
- overnight shift/day-attribution rule;
- holiday list association.

### 4.3 Holiday calendar

Fields:

- date, name, holiday type (`PUBLIC`, `ORGANIZATION`, `OPTIONAL`, `PROJECT`);
- applicable organizations/engagements/projects/locations/calendars;
- full/half day;
- effective version;
- source and approver.

Optional holidays require an employee selection/leave rule rather than universal exemption.

### 4.4 Employee-date override

Admin can mark a normally off Saturday/Sunday as working, or a working date as non-working, for an employee or group. Required:

- affected date(s);
- resulting classification and expected minutes;
- reason;
- approver if after notification/attendance capture;
- impacted-month recalculation preview;
- version/audit.

An override after attendance close triggers reopen impact rules.

---

## 5. Leave policy and types

### 5.1 Leave type configuration

| Field | Examples/rules |
|---|---|
| code/name | `CL`, `SL`, `PL`, `COMP_OFF`, `LWP` |
| paid/unpaid | boolean |
| balance-tracked | false for LWP; usually true for paid leave |
| units | days or half-days; minimum increment configurable |
| applicability | employee groups/policies |
| accrual rule | monthly, annual grant, none |
| carry-forward/expiry | configurable |
| negative balance | convert excess to LWP by default |
| advance notice | configurable; emergency reason can bypass with audit |
| attachment requirement | e.g. medical leave threshold |
| approver policy | reference to PRD 03 |
| weekend/holiday sandwich | explicit configurable rule; default disabled |
| cancellation rule | before/after start and approval requirements |

### 5.2 Policy versioning

A leave request is evaluated against the policy version applicable to each leave date. A later policy edit does not recalculate approved historical leave automatically.

---

## 6. Leave-balance ledger

Do not store an editable balance as the sole truth. Store immutable ledger entries and derive balances.

### 6.1 Ledger entry types

- `OPENING_BALANCE`
- `MONTHLY_ACCRUAL`
- `ANNUAL_GRANT`
- `MANUAL_ADJUSTMENT_CREDIT`
- `MANUAL_ADJUSTMENT_DEBIT`
- `LEAVE_RESERVED`
- `LEAVE_CONSUMED`
- `LEAVE_RELEASED`
- `EXPIRY`
- `CARRY_FORWARD`
- `MIGRATION_CORRECTION`

Each entry includes employee, leave type, quantity, effective date, source, idempotency key, reference object and audit actor.

### 6.2 Accrual job

- Runs once per policy period using a deterministic key `(employee, leave_type, accrual_period, policy_version)`.
- Re-running creates no duplicate.
- Pro-ration for join/exit/eligibility follows policy configuration.
- Admin preview shows eligible employees, credits, skips and validation failures.
- Reversal is a compensating ledger entry, not deletion.

### 6.3 Balance views

Display:

- opening/accrued/adjusted;
- reserved pending requests;
- consumed approved leave;
- available balance;
- expiring quantity/date;
- projected balance for a future leave request;
- LWP resulting from insufficient balance.

### 6.4 Manual adjustment

Requires quantity, effective date, reason, optional evidence, authority and second approval if above configured threshold. It cannot change salary or payment amounts.

---

## 7. Employee administration workflows

### 7.1 Add employee

1. Enter/import identity and employment details.
2. Run duplicate check.
3. Assign calendar, attendance/leave policies and source mode.
4. Assign engagement/project with dates.
5. Add opening leave balance ledger entries.
6. Link or invite user identity.
7. Optionally map/test greytHR employee.
8. Activate; write audit and notifications.

### 7.2 Edit employee

- Show impact preview for calendar/policy/source/allocation changes.
- Effective date mandatory.
- Historical effective date that touches a closed month requires reopen/migration authority.
- Current record remains; create a new version/range.

### 7.3 Disable/enable

- Disable reason and effective timestamp mandatory.
- Open attendance session must be resolved before or by an exception.
- Enabling restores access only if employment and memberships are active.

### 7.4 Exit

- Capture last working date, exit reason and expected final attendance date.
- End allocations/identity access prospectively.
- Resolve open leave requests/sessions.
- Preserve historical evidence and allow authorized final corrections.

---

## 8. Bulk operations

- CSV import with dry-run and row-level error codes.
- Bulk assignment/calendar/policy updates with effective date.
- Bulk leave opening balance/accrual adjustment.
- Preview affected employees/months and require explicit confirmation.
- One import job is idempotent by file hash + template version; re-upload offers resume/reprocess or new-version behavior.

See PRD 18 for templates.

---

## 9. UI requirements

### Employee list

- Active/disabled/exited, project/team, source, calendar and exception badges.
- Search by name/number/email/project.
- Bulk actions are permission gated.

### Employee profile tabs

1. Overview.
2. Engagement/project allocations timeline.
3. Calendar/holidays/overrides.
4. Leave balances and ledger.
5. Attendance summary/exceptions.
6. Deliverable assignments.
7. Integration mapping/sync history.
8. Audit.

### Calendar designer

- Weekly pattern visual editor.
- Holiday import and duplicate detection.
- Employee override preview.
- “Which policy applies on date?” diagnostic tool.

---

## 10. Acceptance criteria and tests

- A disabled employee cannot check in, but prior attendance remains visible.
- An employee with Saturday/Sunday off can be assigned a working Saturday for one date; expected-day calculation changes only for that date.
- A calendar change effective August does not alter July.
- Concurrent project allocations totaling 110% are rejected unless authorized override reason is supplied.
- Mid-month allocation change produces two non-overwritten ranges and correct day-level roster evidence.
- Monthly leave accrual job run twice creates one ledger credit.
- Approved leave reserves/consumes balance; rejection/cancellation releases it through ledger entries.
- A request exceeding paid balance splits permitted units into paid leave and LWP according to policy.
- Employee exit prevents future allocation/check-in and does not remove them from June/July reports.
- A historical effective-date edit touching a closed month is blocked until reopen or migration workflow.
