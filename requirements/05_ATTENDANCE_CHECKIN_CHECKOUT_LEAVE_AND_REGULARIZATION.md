# 05 — Attendance, Check-in/Check-out, Leave and Regularization

**Version:** 1.0
**Status:** Build specification
**Related:** 04, 06, 08, 11, 13, 16

---

## 1. Objective

Resolve every expected employee-day into a transparent, policy-based attendance outcome suitable for monthly procurement evidence, while supporting internal punches, leave, regularization, imported history and greytHR-authoritative records.

---

## 2. Core records

### 2.1 Attendance event

Immutable raw event:

- employee, event type (`CHECK_IN`, `CHECK_OUT`, `BREAK_START`, `BREAK_END`, `ADMIN_PUNCH`, `IMPORTED_PUNCH`);
- occurred timestamp UTC and original timezone/local date;
- received/recorded timestamp;
- source (`INTERNAL_WEB`, `INTERNAL_MOBILE`, `GREYTHR`, `CSV_IMPORT`, `ADMIN_CORRECTION`);
- client-generated idempotency key;
- optional device/IP/geolocation precision subject to policy;
- evidence/justification for admin/import;
- supersession/reversal link; no destructive edit.

### 2.2 Attendance session

Paired interval derived from check-in/check-out:

- start/end event IDs;
- gross minutes;
- paid/unpaid break minutes;
- net worked minutes;
- session status (`OPEN`, `CLOSED`, `INVALID`, `SUPERSEDED`);
- validation warnings.

### 2.3 Attendance day

One computed record per employee and local work date:

- expected-day classification and minutes;
- source authority;
- sessions/net minutes;
- leave units/type;
- regularization/correction refs;
- final attendance status;
- exception flags;
- calculation policy/version;
- computed/finalized timestamps;
- snapshot inclusion/version.

---

## 3. Day and shift attribution

- Default work date is the local calendar date of check-in.
- Overnight calendar policies define a cutoff, e.g. checkout before 06:00 belongs to prior work date.
- One event cannot belong to two work dates.
- Daylight-saving changes are handled by timezone libraries; India has none today but the model is global.
- Server timestamps are authoritative for online events; client time is captured only as diagnostic metadata.

---

## 4. Internal check-in/check-out flow

### 4.1 Check-in

Preconditions:

- authenticated active employee;
- active membership and employment on work date;
- internal attendance allowed by source policy;
- no open session;
- date not locked/closed;
- optional location/device policy passes or records exception.

Action:

1. Server writes idempotent check-in event.
2. Opens a session.
3. Returns time, expected minutes, calendar state and checkout guidance.
4. Notifies only when policy requires.

Duplicate retries with same idempotency key return the original event. A second independent check-in while open is rejected with recovery options.

### 4.2 Check-out

Preconditions: one open session. Action closes session, computes net minutes and recalculates attendance day. Checkout does not automatically make the day final while leave/regularization/import sync is pending.

### 4.3 Multiple sessions

Allowed for split workdays:

- only one open session at a time;
- sessions may not overlap;
- total net minutes is the sum of valid sessions;
- gaps are not worked time unless policy marks a paid break;
- admin can invalidate/supersede erroneous sessions with evidence.

### 4.4 Missing checkout

**Normative rule:** A missing checkout must never cause the system to create a synthetic checkout or fabricate worked minutes.

- At configured cutoff, mark `MISSING_CHECKOUT` exception.
- Do not synthesize a checkout.
- Employee receives regularization prompt; manager/admin queue receives aging item.
- Provisional minutes may be shown but not counted in final evidence unless policy explicitly allows a capped provisional value; default is zero/unresolved.

---

## 5. Attendance calculation policy

All thresholds are configuration values. Initial defaults:

- full-day expected net minutes: **540 (9 hours)**;
- half-day threshold: **270 (4 hours 30 minutes)**;
- minimum meaningful presence threshold: configurable, default 1 minute;
- grace/rounding: zero by default; any grace must be explicit;
- unpaid break: deducted using events or policy; not hidden in elapsed span.

### 5.1 Classification truth table for a normal full working day

| Net approved work | Approved leave | Default final result |
|---:|---|---|
| ≥ full-day threshold | none | `PRESENT_FULL_DAY` |
| half-day to < full-day | approved half-day paid leave | `PRESENT_HALF_PLUS_PAID_LEAVE_HALF` |
| half-day to < full-day | approved half-day LWP | `PRESENT_HALF_PLUS_LWP_HALF` |
| half-day to < full-day | none, approved regularization to full | `PRESENT_FULL_DAY_REGULARIZED` |
| half-day to < full-day | none, pending/denied regularization | `SHORT_HOURS_HALF_DAY_EXCEPTION`, then half-day LWP/leave per resolution |
| >0 to < half-day | approved full-day regularization | `PRESENT_FULL_DAY_REGULARIZED` only with explicit decision/evidence |
| >0 to < half-day | approved full-day leave | `PAID_LEAVE`/`LWP` plus informational punch anomaly |
| >0 to < half-day | none | `ABSENT_OR_FULL_DAY_EXCEPTION`, resolved to leave/LWP/absence |
| 0 | approved leave | leave status |
| 0 | none | `ABSENT` or `LWP` according to policy |

The system must not automatically consume paid leave without a configured request/auto-conversion policy and user/manager visibility. Default: create an exception requiring regularization or leave/LWP resolution.

### 5.2 Non-working dates

| Calendar state | Events/leave | Result |
|---|---|---|
| Weekly off | no events | `WEEKLY_OFF` |
| Holiday | no events | `HOLIDAY` |
| Working-day override | normal rules | attendance required |
| Weekly off/holiday with work | approved work | `WORKED_ON_OFF_DAY`; comp-off eligibility per policy |
| Optional holiday selected | approved | optional holiday leave status |

### 5.3 Half-day calendar

Expected minutes and thresholds come from that day's calendar policy. A half-day expected day can be fully present at its configured expected minutes; it is not reported as attendance failure.

---

## 6. Breaks and duration integrity

- If break events are enabled, validate paired break start/end and subtract unpaid breaks.
- If a fixed unpaid break is configured, deduct it only after a configured gross-duration condition; display the deduction.
- Manual break correction requires reason.
- Negative/overlapping/impossibly long sessions are invalid and routed to exception review.
- Maximum session/day duration is configurable; default warning above 16 hours.
- Rounding, if used, must be symmetric, documented and reflected in raw vs credited minutes.

---

## 7. Leave application workflow

### 7.1 Request fields

- employee and date range;
- leave type;
- full/first-half/second-half units per date;
- reason and attachments where required;
- emergency/retroactive indicator;
- contact/handover notes optional;
- projected paid balance and LWP split;
- policy version.

### 7.2 Validation

- dates within employment and policy eligibility;
- no overlap with approved/pending leave unless replacement flow;
- weekly-off/holiday exclusion/sandwich according to policy;
- balance reservation for tracked paid leave;
- no conflict with locked attendance unless reopen/retro authorization;
- minimum notice and attachment rules;
- approver exists.

### 7.3 States

`DRAFT → SUBMITTED → PENDING_APPROVAL → APPROVED | REJECTED | CHANGES_REQUESTED | CANCELLED`.

For mixed paid/LWP request, retain a per-day/per-unit breakdown. Approval posts consumption entries when effective according to configured policy; cancellation posts releases/reversals.

### 7.4 Advance and retro leave

Advance leave follows normal workflow. Retro leave is allowed only within configured window and before attendance close by default. Beyond it, use reopen/correction authority and mark retrospective provenance.

---

## 8. Attendance regularization

### 8.1 Reasons

Configurable catalog including:

- missed check-in/out;
- official work outside system;
- system/integration outage;
- approved travel/offsite/customer visit;
- incorrect source punch;
- emergency;
- manager-approved short hours;
- other with mandatory details.

### 8.2 Request fields

- affected date/session/event;
- proposed corrected times or requested classification;
- reason code and narrative;
- evidence attachment/link;
- requested outcome (`CORRECT_PUNCH`, `CREDIT_FULL_DAY`, `CREDIT_HALF_DAY`, `MARK_OFFICIAL_DUTY`, etc.);
- declaration checkbox;
- source data preview.

### 8.3 Review

Reviewer sees raw events, calendar, leave, prior requests, source sync, proposed effect and monthly-evidence impact. Decisions:

- approve as requested;
- approve with modified values/outcome;
- reject;
- request information;
- route to higher approver.

Comments are mandatory for modification/rejection and for full-day credit below half-day threshold.

### 8.4 States

`DRAFT → SUBMITTED → UNDER_REVIEW → INFO_REQUESTED → APPROVED | REJECTED | CANCELLED | SUPERSEDED`.

Approved correction creates new events/adjustment records and recomputes the day; it never edits raw source events.

---

## 9. Admin correction

Allowed for authorized users when employee self-service is impossible or source data is demonstrably wrong.

Mandatory:

- reason code and narrative;
- before/after preview;
- supporting evidence;
- explicit source/authority;
- second approval for closed-month/high-impact corrections as configured;
- employee notification unless legal/security exception;
- audit alert.

Admin correction cannot override a greytHR-authoritative day without using the source-conflict workflow in PRD 06.

---

## 10. Source precedence and conflict

Per employee-day, one final authoritative source is selected based on the effective attendance-source policy:

1. authorized historical/correction override, only through approval;
2. greytHR-authoritative record when configured;
3. internal attendance record when configured;
4. unresolved/conflict — no final result.

When two sources disagree beyond configured tolerance:

- preserve both source records;
- mark `SOURCE_CONFLICT`;
- do not silently choose based on latest timestamp;
- route to reconciliation with proposed authority;
- capture decision and reason.

---

## 11. Daily and monthly operations

### 11.1 Daily jobs

- expected-day generation for active employees;
- missing check-in reminder at configurable local time;
- open-session/missing-checkout detection;
- recomputation after events/leave/sync;
- exception aging and escalation;
- greytHR sync/reconciliation where configured.

### 11.2 Monthly attendance close

Preconditions:

- roster finalized for represented month;
- all employee-days generated;
- no blocking unresolved source conflicts, missing punches or pending leave/regularization unless exception policy explicitly permits;
- source sync freshness meets policy;
- reviewer sign-off if configured.

Output:

- employee-day snapshot;
- summary counts/minutes/leave/LWP/absence;
- exception appendix;
- source/provenance report;
- checksum and version;
- close actor/time.

### 11.3 Reopen

A correction after snapshot close:

1. creates reopen request with impacted employees/dates and reason;
2. identifies affected certification/confirmation/package/invoice;
3. requires configured authorization;
4. creates a new attendance snapshot version;
5. invalidates downstream readiness and triggers reconfirmation/package regeneration as needed;
6. preserves all prior versions.

---

## 12. Employee UX

### Today's attendance card

- current local date/time and expected calendar status;
- check-in or checkout CTA;
- active-session duration (informational);
- expected 9-hour target and break handling;
- source mode badge;
- errors and recovery CTA;
- recent sessions and privacy notice.

### Attendance calendar

- day-status legend;
- raw/credited minutes;
- leave/regularization state;
- weekly off/holiday/working override;
- source and last synchronized time;
- “fix issue” CTA where permitted.

### Leave/regularization

- balance/projected LWP;
- approver and SLA;
- timeline/comments;
- cancellation/response actions.

---

## 13. Manager/admin UX

- Exception inbox grouped by missing checkout, short hours, absent, source conflict, pending request and stale sync.
- Filters by engagement/project/employee/date/severity/aging.
- Side-by-side raw source, proposed correction and calculated impact.
- Bulk reminder only; bulk approval is disabled for high-risk corrections by default.
- Month-close readiness page with blocking/non-blocking issues and drill-down.

---

## 14. Notifications and SLAs

Initial configurable defaults:

- missing check-in reminder: 60 minutes after expected start;
- open-session reminder: near expected end and at cutoff;
- regularization submit window: 3 working days after exception;
- manager review: 2 working days;
- escalation: 1 working day after breach;
- month-end exception freeze: configured before evidence generation.

These are policy defaults, not hard-coded dates.

---

## 15. Acceptance tests

### Punch/session

- Two identical check-in retries create one event/session.
- Second check-in with an open session is rejected.
- Multiple non-overlapping sessions sum correctly; gaps do not count.
- Missing checkout remains unresolved; no fabricated time appears in evidence.
- Overnight checkout is attributed to the policy-defined prior work date.
- A 16+ hour session is flagged for review.

### Classification

- Exactly 540 credited minutes yields full present under default policy.
- 539 minutes yields short-hours exception unless grace/regularization applies.
- 270 minutes plus approved half-day paid leave yields half present/half paid leave.
- 269 minutes without full-day leave/regularization yields full-day exception/absence resolution.
- Saturday off yields weekly off; an employee-date working override requires attendance.
- A holiday worked with approval is reported distinctly and can create comp-off credit per policy.

### Leave/balance

- Request within balance reserves and then consumes correct units.
- Request beyond balance produces explicit paid/LWP split, not negative hidden balance.
- Cancellation releases balance through ledger entry.
- Overlapping leave is rejected.
- Retro leave touching closed month requires reopen workflow.

### Regularization/source

- Approved missing checkout creates a correction record and recomputes the day without mutating raw event.
- Rejected request retains original classification and comment.
- Internal and greytHR disagreement creates source conflict; no silent overwrite.
- Reopen and correction create a new attendance snapshot and mark old package superseded.
