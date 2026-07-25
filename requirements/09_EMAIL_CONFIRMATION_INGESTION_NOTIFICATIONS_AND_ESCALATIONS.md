# 09 — Email Confirmation, Reply Ingestion, Notifications and Escalations

**Version:** 1.0
**Status:** Build specification
**Related:** 03, 07, 08, 10, 13, 14

---

## 1. Objective

Make required monthly communications auditable business records and capture authentic Reliance product-owner confirmation through secure, traceable mechanisms. Preserve automation without treating delivery receipts, reminders or silence as approval.

**Normative rule:** Silence is not confirmation. A delivery/read receipt, reminder timeout, auto-reply or absence of objection must never create a confirmed state.

---

## 2. Required business communications

### 2.1 Monthly commitment email

Triggered after plan approval/freeze.

**To:** configured ArrowFoundry delivery contacts and Reliance product owners/approvers.
**CC:** Reliance/Jio Central Procurement, plus configured governance observers.

Includes:

- engagement/project/month;
- approved plan version and approval date;
- deliverable table with owner, target date, acceptance summary and Linear links;
- roster/allocation summary where policy requires;
- approved exceptions/revisions;
- secure link to plan;
- generated attachment/manifest checksum.

### 2.2 Delivery certification email

Triggered when monthly certification completes.

Includes deliverable decisions, observations/deferred items, approver names/timestamps, Linear snapshot summary and link to certification record.

Recipients mirror configured stakeholder groups with Procurement CC.

### 2.3 Consolidated monthly confirmation request

Triggered only when readiness gate passes.

Includes the three core proof pillars requested by Procurement:

1. employee roster/attendance summary and exception disclosure;
2. approved monthly deliverables/Linear snapshot;
3. delivery certification/approval summary.

Also includes invoice reference if already uploaded, package draft link, confirmation deadline, and clear actions: **Confirm**, **Request Correction**, **Reject/Do Not Confirm**.

### 2.4 Confirmation outcome email

Sent after verified confirmation/rejection/change request to all original participants and Procurement CC, with actor/source/timestamp and resulting invoice-readiness state.

### 2.5 Other transactional notifications

Attendance exceptions, leave/regularization, approvals, integration health, invoice/package/payment status and reopen events. Their detailed triggers live in related PRDs.

---

## 3. Notification architecture

### 3.1 Components

- domain event producer;
- notification rule resolver;
- recipient/group snapshot resolver;
- template/version renderer;
- outbox record;
- channel adapter (`EMAIL`, `IN_APP`, optional `TEAMS/SLACK/WHATSAPP` later);
- delivery-attempt/retry processor;
- inbound email/secure-action processor;
- audit/evidence archive.

Use a transactional outbox so a business transition and its notification request cannot diverge.

### 3.2 Notification record

- event/correlation ID;
- template and template version;
- business object/version;
- resolved To/CC/BCC with role attribution;
- subject and rendered-body hash;
- attachment refs/hashes;
- provider/message/thread IDs;
- queued/sent/delivered/bounced/failed timestamps/status;
- attempts and error category;
- superseded/resend relation;
- retention/legal hold.

A provider “delivered” status proves transport only, not business confirmation.

---

## 4. Template governance

- Templates are versioned, previewable and testable.
- Mandatory legal/procurement text cannot be removed without template-publish authority.
- Dynamic fields are typed and validated; unresolved required variables block send.
- Email content uses accessible HTML plus plain-text alternative.
- Attachments and links identify version and classification.
- Subject includes stable engagement/month token for threading, e.g. `[AF-RI][2026-06][CONFIRMATION][<request-id>]`.
- Production edits publish a new version and do not change archived messages.

---

## 5. Confirmation capture methods

Use one or more, with source explicitly recorded.

### 5.1 Preferred: secure confirmation link

1. Email contains a single-use, time-limited request link with opaque token.
2. User authenticates through SSO/OTP as policy requires.
3. System verifies the authenticated identity is an eligible confirmer.
4. User sees exact package/summary version and differences since prior request.
5. User selects confirm, request correction or reject; comment required for non-confirm.
6. Action signs object/version hash and records source `SECURE_EMAIL_LINK`.
7. Token is invalidated; replay returns prior outcome or is denied.

A forwarded link alone does not grant authority.

### 5.2 Verified email reply ingestion

Use a dedicated controlled mailbox/provider adapter where authorized.

- Send from/reply-to a monitored address.
- Include stable request/thread token in headers/body.
- Ingest provider message metadata and MIME content through webhook/subscription or bounded polling.
- Match by in-reply-to/references/message IDs and request token.
- Verify sender address against the eligible confirmer's verified identity; domain allowlist is supportive but not sufficient alone.
- Record available SPF/DKIM/DMARC/provider-authentication results.
- Parse explicit confirmation intent conservatively.
- Ambiguous replies route to manual review; AI classification may suggest but never autonomously confirm.
- Store original message or immutable provider reference/hash according to security policy.
- Attachments are malware scanned and access controlled.

Accepted explicit forms may include configured phrases such as “Confirmed/Approved for June 2026,” but the system must display parsed interpretation for reviewer verification where ambiguity exists.

### 5.3 In-application confirmation

Eligible product owner opens the month and confirms after reviewing the exact version. Source is `IN_APP`; confirmation outcome email is still sent.

### 5.4 Manual evidence recording fallback

For historical or integration-unavailable cases only:

- authorized user uploads original `.eml`/`.msg`/PDF/screenshot or provider export;
- enters sender, recipients, subject, message ID, sent/received time and represented decision;
- system hashes file and checks sender eligibility/thread/request;
- second authorized reviewer validates by default;
- source is `MANUAL_EVIDENCE`, clearly visible in package;
- system audit timestamp is current; represented historical timestamp is separate.

Manual record must not be labeled automatically verified.

---

## 6. Confirmation object and states

### 6.1 Request fields

- engagement month;
- requested confirmation scope and exact snapshot/package version IDs;
- eligible confirmer policy and quorum;
- To/CC snapshot;
- requested/due timestamps;
- request email/message IDs;
- secure token metadata (hashed token only);
- reminders/escalations;
- supersedes request.

### 6.2 States

`DRAFT → QUEUED → SENT → AWAITING_RESPONSE → CONFIRMED | CHANGES_REQUESTED | REJECTED | EXPIRED | CANCELLED | SUPERSEDED`.

Transport sub-status is separate: `QUEUED`, `SENT`, `DELIVERED`, `BOUNCED`, `FAILED`.

### 6.3 Decision fields

- actor identity and verified sender/address;
- actor authority/role snapshot;
- decision/comment;
- source method;
- decision timestamp and provider received timestamp;
- message/token/session evidence;
- object/version checksums;
- verification confidence/status;
- reviewer if manual/ambiguous;
- audit record.

### 6.4 Quorum

Support `ANY_ONE`, `ALL`, `N_OF_M`, ordered approval and project-specific decisions. Monthly confirmation completes only when policy quorum is met. Conflicting decisions block and route to governance.

---

## 7. Change request/rejection workflow

1. Confirmer identifies attendance, deliverable, certification or package issue and comments.
2. Confirmation request becomes `CHANGES_REQUESTED`/`REJECTED`.
3. Invoice readiness is blocked.
4. Governance creates correction/reopen action linked to issue.
5. Affected snapshot/package is revised through its source workflow.
6. System generates a new summary/package version and sends a new confirmation request with diff.
7. Prior request/outcome stays archived.

Email replies must not directly mutate source data.

---

## 8. Reminder, escalation and fallback

### 8.1 Configurable schedule

Example, not hard-coded:

- first reminder before/at due date;
- second reminder to confirmer plus delegate;
- escalation to governance/manager;
- Procurement visibility after breach;
- reassign to authorized alternate/delegate;
- request expiration and creation of a new request where needed.

### 8.2 No-response rule

No response never becomes `CONFIRMED` by default. It remains overdue/expired and blocks invoice readiness unless an authorized Procurement exception is recorded under PRD 10.

### 8.3 Delivery failure

- retry transient failures with exponential backoff/jitter;
- bounce or permanent invalid address creates blocking recipient issue;
- recipient resolver suggests alternate active group member;
- resend creates linked attempt/version, not duplicate hidden message;
- UI exposes sent/failed state and provider IDs.

---

## 9. Recipient and confidentiality controls

- Resolve recipients at event time and snapshot them.
- Validate required stakeholder categories, not just non-empty addresses.
- Deduplicate To/CC while retaining reason/role.
- Prevent broad distribution of employee-level attendance when a summary suffices; package access requires authentication.
- Prefer secure links over large PII attachments.
- Expiring signed URLs and access logs for downloads.
- BCC disabled for procurement-critical communications unless policy explicitly permits and records it.
- External-recipient warning and data-classification banner.

---

## 10. In-app notification center

- unread/task/overdue categories;
- object link and action CTA;
- role/scoped filtering;
- mark read does not dismiss business task;
- notification preferences may reduce informational reminders but cannot disable mandatory approval/procurement notices;
- full delivery history visible to authorized admins.

---

## 11. Audit and evidence

Archive:

- rendered subject/body and template version;
- recipient/group snapshot;
- attachments and checksums;
- provider/message/thread identifiers;
- delivery attempts;
- inbound reply/secure action evidence;
- parser/reviewer decisions;
- resulting domain transition.

A resend or corrected request retains the original. Email evidence included in package must have a human-readable rendering plus metadata manifest.

---

## 12. Acceptance tests

- Commitment email cannot send when required Procurement CC group is absent.
- Message uses approved/frozen plan version and checksum; later plan change does not alter archived email.
- Provider delivery status alone does not change confirmation state.
- A forwarded secure link opened by an unauthorized identity cannot confirm.
- Reusing an already-consumed secure token does not create a second action.
- Verified reply from an eligible sender in the matching thread can be recorded with message metadata.
- Reply from an unrecognized address/domain is quarantined, not confirmed.
- Ambiguous text such as “looks okay, discuss tomorrow” does not auto-confirm.
- Manual historical email evidence requires mandatory metadata/file hash and second review where configured.
- Confirmation against package version 1 becomes superseded when correction creates version 2; a new confirmation is required.
- Reminder/escalation fires without creating a false approval.
- Duplicate domain-event processing creates one outbox notification.
- Bounce is visible and creates a recipient-resolution task.
