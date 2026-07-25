# 07 — Monthly Deliverable Planning and Linear Integration

**Version:** 1.0
**Status:** Build specification
**Related:** 02, 03, 08, 09, 11, 13, 19

---

## 1. Objective

Create an approved, immutable monthly baseline of measurable Reliance-owned deliverables before execution, trace each deliverable to one or more Linear issues, and retain both current and historical issue state without treating ticket state as business acceptance.

---

## 2. Monthly plan

### 2.1 Plan identity

One logical plan per engagement month, with immutable versions:

- plan ID, engagement month, version number;
- title/summary/business outcomes;
- owner group and coordinator;
- draft/submission/approval/freeze timestamps;
- baseline type (`ON_TIME`, `LATE_APPROVED`, `HISTORICAL_RECONSTRUCTED`);
- prior-version/revision reason;
- approval record and commitment-email record;
- checksum.

### 2.2 States

`DRAFT → READY_FOR_REVIEW → PENDING_APPROVAL → APPROVED → FROZEN → SUPERSEDED`.

`CHANGES_REQUESTED`, `REJECTED` and `CANCELLED` are terminal for a specific submission version; a revised version is created.

### 2.3 Completeness gate

A plan cannot be submitted unless:

- at least one deliverable exists or an explicit “no planned deliverables” exception is approved;
- every deliverable meets mandatory fields;
- owners/approvers/contact groups are active for the month;
- resource allocation and target-date validations pass;
- inaccessible/invalid Linear links are resolved or explicitly excepted;
- dependencies/risks are stated;
- plan email recipient preview includes ArrowFoundry, Reliance product stakeholders and Central Procurement CC.

---

## 3. Deliverable model

### 3.1 Mandatory fields

| Field | Requirement |
|---|---|
| `deliverable_code` | unique within plan version; stable lineage across revisions |
| `title` | concise outcome |
| `description` | sufficient implementation/business context |
| `business_objective` | why it matters |
| `project_id` | one primary project; cross-project dependencies allowed |
| `product_owner_id/group` | at least one authorized Reliance owner |
| `vendor_owner_id/group` | at least one ArrowFoundry owner |
| `priority` | configured enum/rank |
| `target_completion_date` | within or explicitly beyond represented month |
| `acceptance_criteria` | one or more independently testable criteria |
| `evidence_expectations` | demo, document, deployment, test, metrics, etc. |
| `dependencies` | none stated explicitly or linked records |
| `risk_and_assumptions` | mandatory “none” or details |
| `linear_links` | at least one by default; exception requires reason/approval |
| `assigned_employees` | one or more contributors unless not applicable |
| `delivery_category` | feature, platform, integration, quality, operations, research/POC, support, other |

Optional: weighting, business KPI, milestone children, attachments, design links, repositories and environments. No salary/cost fields.

### 3.2 Acceptance criteria

Store as child records with ID, statement, validation method, expected result and mandatory/optional flag. Delivery certification records a decision against each criterion or explains aggregate decision.

### 3.3 Deliverable dependencies

- internal deliverable dependency;
- Linear issue dependency (informational);
- external team/system dependency;
- target resolution date and owner;
- blocking/non-blocking classification.

Detect dependency cycles among deliverables.

### 3.4 Employee assignment

- employee must be active/allocated for relevant dates, or a warning/authorized exception appears;
- assignments are effective-dated and snapshotted;
- Linear assignees can be compared/suggested but not silently create employee assignments.

---

## 4. Plan approval and revision

### 4.1 Approval

Approver reviews:

- deliverables and acceptance criteria;
- Linear link validity and snapshot;
- roster/allocation coverage;
- dates/dependencies/risks;
- exceptions and late status;
- exact email preview.

Approval signs the plan version checksum. On quorum:

1. version becomes approved/frozen;
2. baseline and Linear plan-time snapshot are generated;
3. commitment email is queued;
4. month progresses to `PLAN_APPROVED`/eligible `ACTIVE`;
5. any edit requires revision workflow.

### 4.2 Revision after freeze

Reasons: scope change, replacement, dependency failure, correction, employee/owner change or historical reconstruction.

- Clone current version.
- Highlight additions/removals/field changes.
- Require impact and reason.
- Reapprove according to policy.
- Send revision communication with diff.
- Original baseline remains; reports distinguish original and latest approved baseline.
- Delivery certification evaluates against the effective approved version, while preserving original commitment metrics.

Emergency changes are not untracked edits.

---

## 5. Linear integration design

### 5.1 Authentication

Production integration uses a Linear OAuth2 application, preferably an `app` actor/service-account installation with least privilege. Initial requirement is read access plus webhook operation; do not request `write` or `admin` unless a later approved capability requires it.

- OAuth authorization uses state and PKCE where applicable.
- Store access/refresh credentials server-side in secret storage.
- Refresh-token rotation/replay handling follows Linear's current OAuth documentation.
- Personal API keys may be used only for local development or a controlled temporary migration, never the long-term shared production connection.

### 5.2 API client

- Use official `@linear/sdk` or typed GraphQL client against `https://api.linear.app/graphql`.
- Check GraphQL `errors` even when HTTP status is 200.
- Query only fields needed for evidence.
- Respect response rate-limit headers and current provider limits; do not hard-code a stale numeric allowance.
- Paginate and filter server-side.

### 5.3 Issue linking

Users can:

- paste a Linear issue URL;
- enter an identifier such as `TEAM-123`;
- search within authorized workspace/team;
- select multiple issues.

On link:

1. Parse identifier/URL and reject non-Linear/unrecognized format.
2. Resolve through API.
3. Verify workspace/team access.
4. Store immutable Linear issue UUID plus identifier and URL.
5. Fetch current metadata and display freshness.
6. Prevent duplicate link within deliverable.
7. Warn when the same issue is linked to multiple deliverables; require rationale or split.

Broken/inaccessible links remain visible with error state; the plan cannot pass its completeness gate unless an authorized exception exists.

### 5.4 Synchronized issue fields

- workspace/team ID/name/key;
- issue UUID, identifier, URL;
- title and limited description excerpt;
- workflow state ID/name/type/category;
- priority;
- assignee ID/name/email where permitted;
- project/cycle;
- labels;
- due date;
- created/updated/completed/canceled/archived timestamps;
- parent/sub-issue identifiers where used;
- source fetched timestamp and payload hash.

Do not copy authenticated Linear file assets into evidence unless explicitly downloaded through authorized server flow and allowed by policy.

### 5.5 Normalized state

Preserve original state and map to:

- `BACKLOG`
- `UNSTARTED`
- `STARTED`
- `COMPLETED`
- `CANCELED`
- `UNKNOWN`

Mapping is configured from Linear workflow-state type/category and versioned. Custom state names are not interpreted by string guessing alone.

### 5.6 Status semantics

- Linear state is execution evidence, not delivery acceptance.
- `COMPLETED` does not automatically mark a deliverable delivered or certified.
- A deliverable can be accepted with some linked issues open if the product owner records rationale.
- A deliverable can be rejected with all linked issues Done if acceptance criteria were not met.
- Current state and plan/month-end snapshots are displayed separately.

---

## 6. Webhook and reconciliation

### 6.1 Webhook receiver

- Public HTTPS server/edge endpoint.
- Preserve raw body for signature verification.
- Use Linear's signature-verification mechanism/official SDK helper.
- Validate timestamp/replay window and expected workspace/connection.
- Persist delivery ID/event fingerprint before processing.
- Return HTTP 200 within five seconds after durable enqueue; process asynchronously.
- Unsupported/invalid payloads are quarantined and alerted.

Linear currently retries failed deliveries with backoff and can disable an unresponsive webhook; the platform must therefore monitor last delivery and run reconciliation.

### 6.2 Event processing

- At minimum process issue create/update/remove/archive and OAuth-app revoke relevant to linked issues.
- Upsert latest issue state idempotently.
- Record event history and changed fields where supplied.
- Recompute deliverable progress view.
- Notify only on configured material transitions, not every label edit.

### 6.3 Reconciliation jobs

- manual refresh per issue/deliverable;
- scheduled delta query ordered/filtered by `updatedAt`;
- nightly linked-issue reconciliation;
- plan freeze snapshot job;
- month-end snapshot job;
- post-close discrepancy detector.

Webhooks provide freshness; reconciliation provides completeness. Neither should poll every issue on every page load.

### 6.4 Connection failure

- revoked/expired authorization sets connection `ACTION_REQUIRED`;
- retain last known data with stale badge;
- planning may be blocked for new links but existing records remain;
- resubscribe/test webhook after reconnection;
- do not lose link UUIDs/history.

---

## 7. Linear snapshots

### 7.1 Plan-time snapshot

Captured at plan approval for every linked issue, including metadata/state and fetched time. A failed fetch is represented explicitly; it is not omitted.

### 7.2 Month-end snapshot

Captured at delivery submission/certification cutoff. Includes:

- current state and relevant timestamps;
- delta from plan snapshot;
- freshness/sync status;
- last webhook/reconciliation event;
- inaccessible/deleted issue flags.

### 7.3 Post-close updates

Later Linear changes update the live view but do not alter snapshots. Evidence package labels live current state versus certified snapshot.

---

## 8. Progress and health views

### Plan/deliverable UI

- issue cards with identifier, title, state, assignee, priority and freshness;
- link/search/remove before freeze;
- plan-time versus current state diff;
- sync errors and manual refresh;
- coverage indicator: deliverables with valid links;
- employee assignee mismatch warning.

### Integration health

- connection and authorization expiry/revocation;
- webhook last received/last verified/error rate;
- reconciliation lag;
- linked issue count, stale/inaccessible count;
- dead-letter/replay queue.

---

## 9. Historical planning

- Import monthly deliverables and Linear URLs/identifiers from June onward.
- Resolve available issues and capture `HISTORICAL_RETRIEVAL` snapshot at import time.
- If true historical Linear state cannot be reconstructed from API/audit exports, do not label current state as month-end state.
- Allow optional upload of historical Linear export/event evidence with source date and checksum.
- Mark snapshot confidence: `SOURCE_EVENT_HISTORY`, `SOURCE_EXPORT`, `CURRENT_STATE_ONLY`, `UNAVAILABLE`.

---

## 10. Notifications

- draft due, incomplete fields/links;
- plan submitted/changes requested/approved;
- commitment email sent/failed;
- Linear connection revoked/stale;
- linked issue canceled/archived/deleted or materially blocked;
- plan revision approval and diff.

---

## 11. Acceptance tests

### Planning

- Plan with missing acceptance criteria/owner/target date cannot submit.
- Invalid/inaccessible Linear link blocks submission unless an authorized exception with reason exists.
- Frozen plan cannot be edited in place; revision creates new version and diff.
- Approval records the exact version checksum and effective approver authority.
- Commitment recipient preview includes required Procurement CC.

### Linear

- URL/identifier resolves to UUID and duplicate link is prevented.
- Same issue linked to another deliverable generates warning/rationale requirement.
- Webhook signature failure is rejected and logged without applying data.
- Duplicate webhook delivery is processed once.
- Webhook endpoint acknowledges only after durable enqueue and processing is asynchronous.
- Revoked OAuth connection marks stale/action-required but does not delete issue history.
- GraphQL partial errors are handled, not treated as total success.
- State mapping preserves original custom state name and normalized category.
- A Done issue does not auto-certify its deliverable.
- Plan-time snapshot remains unchanged after later Linear update.
- Historical import with only current Linear data is labeled `CURRENT_STATE_ONLY`, not falsely month-end.
