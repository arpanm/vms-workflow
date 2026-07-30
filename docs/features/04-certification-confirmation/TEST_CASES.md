# F04 — Certification and Confirmation Test Cases

## Withdrawal/artifact completion regression

- `F04-WITHDRAW-001`: exact-version draft withdrawal records status, audit, event, and idempotency facts without deletion.
- `F04-WITHDRAW-002`: submitted/stale/cross-scope withdrawal is rejected.
- `F04-ARTIFACT-001`: upload records safe SHA-256 metadata as `PENDING`; explicit scan returns `PASSED`.
- `F04-ARTIFACT-002`: malware/missing bytes become `FAILED`/`UNKNOWN` and never become evidence choices.
- `F04-ARTIFACT-003`: PostgreSQL rejects all artifact rewrites outside the one-way scan transition.
- `F04-ARTIFACT-004`: EICAR/executable or checksum-mismatched content becomes `FAILED` and is never selectable evidence.

**Traceability:** RQ-018–RQ-023, RQ-030–RQ-031; PRD 08–09, 13–16, 20–22.
**Test boundary:** Execute local cases with JUnit 5, Spring Boot Test + MockMvc/WebTestClient, Testcontainers PostgreSQL, fake/recorded email/object-storage/scan/inbound adapters, and Playwright against the Vite React application. No local case requires a live Linear workspace, production email account, real sender, controlled mailbox, production SSO/OTP, or provider secret. Cases explicitly marked **external acceptance** require the approved tenant provider configuration and must not be counted as passed from a mock.

## Current evidence status

- [x] The non-external requirements have agent-run local evidence across 58 F04
  backend tests, 64 frontend tests, and 33 F04 Playwright browser-contract
  cases; the associated legacy regressions bring the recorded totals to 107
  backend and 59 Playwright tests.
- [ ] The reported results are awaiting the root agent's final rerun.
- [ ] `T-MSG-007`, `T-CONF-018`, `E2E-F04-PROVIDER-001`, and
  `E2E-F04-PROVIDER-002` remain external acceptance cases. A fixture or
  intercepted browser result cannot close them.

Per-case mappings and preserved red-to-green history are in
[TEST_AUTOMATION.md](TEST_AUTOMATION.md) and
[docs/testing/E2E_REGRESSION_CASES.md](../../testing/E2E_REGRESSION_CASES.md).

## Test data and common assertions

- Seed at least two organizations, one engagement with two projects, active/inactive vendor/client/governance/procurement identities, scoped owner/alternate/delegate, an outsider, and a service identity; include a frozen F03 plan/baseline with linked Linear current/plan-time/month-end captured, failed and unavailable snapshots.
- Seed F02 closed attendance snapshots, no snapshot, superseded snapshot, and disclosed authorized attendance exception. Use deterministic clock, UUIDs, request idempotency keys and stable canonical payloads.
- For every consequential test assert response code/error code, current version/ETag and correlation ID; persisted domain/audit/outbox facts; organization/project scope; immutable source versions; no unexpected provider call; and no salary/payroll/rate/markup data in schema, UI, API, logs, or fixtures.

## Delivery submission and artifact tests

- `T-DEL-001` — Only an active scoped `VENDOR_MANAGER` or explicitly assigned vendor delivery owner can create/edit/submit its engagement-month draft. A contributor may add authorized draft evidence but cannot submit by default; client, Procurement, inactive, cross-project, cross-tenant and service identities are denied without record disclosure.
- `T-DEL-002` — Submission against a missing, non-frozen, superseded, cross-engagement or revision-pending plan fails without creating partial outcomes. The effective F03 baseline/version/checksum is copied by reference and remains unchanged.
- `T-DEL-003` — A submit with any effective baseline deliverable absent, duplicated or containing an unrecognized outcome fails with per-item blockers and leaves the submission editable/draft.
- `T-DEL-004` — `COMPLETED` accepts no comment only when policy treats it as uncomplicated; every other outcome requires comment/cause/impact/next action, and partial/not-complete/deferred additionally requires valid carry-forward/dependency information.
- `T-DEL-005` — Percentage boundaries 0 and 100 are accepted, values below 0/above 100/non-numeric are rejected, and percentage alone cannot decide certification or summary state.
- `T-DEL-006` — Every acceptance criterion must receive a valid vendor response; omitted/duplicate/out-of-baseline criterion IDs fail without changing a submitted version.
- `T-DEL-007` — Required evidence expectations must be met or carry an authorized recorded exception; an attempted month-end Linear snapshot must be `CAPTURED`, `FETCH_FAILED`, or explicit `UNAVAILABLE`, never silently omitted.
- `T-DEL-008` — Upload rejects oversize/disallowed extension/MIME mismatch/formula/active-content filename and quarantines malware/unknown-scan evidence. Scan-passed evidence records safe name, hash, classification, source and version; unsigned/public URLs and failed/quarantined artifacts cannot be viewed or included downstream.
- `T-DEL-009` — Submitted vendor outcomes/evidence/declaration are append-only/read-only. Pre-review withdrawal follows authority/policy; `MORE_INFORMATION_REQUIRED` creates additive response/evidence lineage and cannot overwrite the original submission.
- `T-DEL-010` — Duplicate HTTP submission idempotency key and concurrent same-month submit result in one committed submission/event/outbox task; divergent duplicate payload gets typed conflict and no second version.

## Certification, clarification, and carry-forward tests

- `T-CERT-001` — Only an active scoped product owner/authorized deliverable approver can decide a matching item. Vendor, unassigned client owner, project/tenant outsider, inactive user and service identity are denied; UI controls do not override server authorization.
- `T-CERT-002` — Supported item decisions persist as a certification record separate from vendor submission. `REJECTED`, dependency deferral, `PARTIALLY_ACCEPTED` and `MORE_INFORMATION_REQUIRED` require comment/cause/next action; accepted-with-observations requires observations.
- `T-CERT-003` — Criterion-by-criterion decisions must map to the frozen criteria. An inconsistent aggregate decision is rejected unless its explicit override rationale is present and audited; item decision never edits vendor criterion responses.
- `T-CERT-004` — Linear `Done`/`COMPLETED`, current state changes, webhook replay, missing/inaccessible issue and a 100% vendor percentage never create `ACCEPTED`, a criterion pass, a summary or confirmation eligibility. Authorized decision remains possible with contrary Linear evidence only with rationale.
- `T-CERT-005` — `MORE_INFORMATION_REQUIRED` creates immutable questions/round, non-terminal review status and vendor notification; the vendor response is additive, captures policy-controlled SLA pause/resume, and certification resumes only through an eligible actor. It cannot revise frozen scope.
- `T-CERT-006` — `PARTIALLY_ACCEPTED` requires accepted/rejected scope and creates one origin-to-next-month carry-forward link with cause owner and next action. Duplicate/mismatched next-month link and double-counting the original outcome are rejected.
- `T-CERT-007` — Once all items are terminal, summary generation is deterministic for identical source versions (same canonical manifest/hash) and includes baseline, criterion, decision, evidence, Linear freshness, variance/carry-forward and observations data. Any included source-version change produces a new/superseding summary, never an in-place update.
- `T-CERT-008` — A monthly decision is explicit and policy/authority checked; percentage/count thresholds do not infer `CERTIFIED`. A terminal-item set with no authorized monthly action follows configured policy and is visibly incomplete if action is required.
- `T-CERT-009` — Concurrent item decisions/summary generation lock or use expected version correctly: only one terminal item version/summary/email event exists; stale decision returns conflict/current state rather than overwrite.
- `T-CERT-010` — Overdue certification creates idempotent reminder/escalation/delegate/governance/Procurement tasks but no accepted/certified state. An explicitly configured Procurement exception remains disclosed and is not product-owner approval.

## Messages, reminders, and delivery tests

- `T-MSG-001` — Submission, clarification, certification and confirmation messages snapshot actor-role-attributed To/CC recipients, require ArrowFoundry/Reliance stakeholder/Central Procurement categories where applicable, deduplicate addresses while preserving reason, and block enqueue when a required category is absent.
- `T-MSG-002` — Rendered message uses immutable template version and exact source version/checksum, accessible HTML plus plain text, classified link/attachment manifest and stable engagement/month/thread token. Later plan/certification/request changes cannot alter archived content.
- `T-MSG-003` — Transaction committing a certification/confirmation business transition creates exactly one matching outbox record/event. Reprocessing the same domain event, request retry, worker retry or admin replay cannot duplicate a business effect or email.
- `T-MSG-004` — Fake-adapter transient failure applies bounded exponential backoff/jitter then dead-letters with attempt/provider/error/correlation metadata; authorized replay after correction sends at most once per outbox idempotency key and preserves prior attempts.
- `T-MSG-005` — Delivered, read, bounced, failed, no response, auto-reply and receipt update transport/task status only. Bounce/permanent invalid recipient creates visible recipient-resolution blocker/alternate suggestion, not confirmation or hidden resend.
- `T-MSG-006` — Reminder schedule obeys effective configured due/first/second/escalation/expiry policy, delegate/alternate and notification preferences; informational opt-out cannot disable mandatory approval/Procurement notice, and duplicate scheduler runs produce one stage/reminder record.
- `T-MSG-007` — **External acceptance:** approved mail provider, sender/dedicated mailbox, retention policy and recipient groups complete sandbox/live send. Validate provider IDs/thread metadata, bounce/callback signature and restricted archive access without exposing credentials, raw restricted content or secrets.

## Confirmation request, action, quorum, and correction tests

- `T-CONF-001` — Readiness permits a request only with effective frozen plan, delivery submission, terminal certification and summary, roster/closed attendance snapshot or authorized disclosed exception, month-end Linear captured/unavailable status, classified observations, active recipients/confirmers and no pending plan revision. Each missing condition returns named blocker/owner/action and no request/outbox row.
- `T-CONF-002` — A request stores exact attendance/plan/baseline/certification/package-if-present version IDs and canonical hashes, recipient/eligible-role/quorum snapshots, due date and request lineage. Subsequent source change invalidates readiness and cannot mutate the original request scope.
- `T-CONF-003` — State transition progression is enforced: draft/queued/sent/awaiting-response and terminal/superseded/expired/cancelled paths are valid only through their service operation; transport state is stored separately and cannot transition a business state.
- `T-CONF-004` — Secure link token is high-entropy, opaque, stored only as hash, single-use, expiring, request/version-bound and invalidated atomically on action. Database/API/normal logs/React page never expose token hash, plaintext token or provider secret.
- `T-CONF-005` — An eligible authenticated requester sees exact scoped version/diff and may confirm, request correction or reject. Non-confirmation requires comment; action stores actor authority, source, timestamps, session/token evidence, canonical hash and audit record, then queues outcome notice.
- `T-CONF-006` — A forwarded/stolen token used by another authenticated identity, anonymous user, stale/disabled user, wrong-org/project actor, CSRF request, wrong request/version or expired token is denied without scope disclosure and creates no action. Rate limiting does not leak token validity.
- `T-CONF-007` — Replaying an already-consumed token/action/idempotency key is idempotent only for the authorized original actor and returns the prior outcome; concurrent secure actions create one action/quorum contribution and no duplicate notice. A distinct conflicting action follows quorum/conflict policy.
- `T-CONF-008` — `ANY_ONE`, `ALL`, `N_OF_M`, ordered and project-specific configured quorum count only current eligible snapshot members. Duplicate, revoked, unassigned or out-of-order actions do not count; conflicting decisions block and create governance review instead of overwriting a confirmation.
- `T-CONF-009` — In-app confirmation has the same identity/version/quorum/audit guarantees as secure-link confirmation, source `IN_APP`, and still creates confirmation outcome communication.
- `T-CONF-010` — Correction/rejection blocks invoice-readiness handoff, preserves original request/action, creates linked governance task and never directly mutates attendance/plan/submission/certification. A corrected summary/package version supersedes request v1, archives it and produces v2 with a visible diff and fresh confirmation requirement.
- `T-CONF-011` — Silence, elapsed due date, reminder, receipt/read-status, delivery status, auto-reply and no objection never set `CONFIRMED`; they remain overdue/expired/blocking unless an explicit authorized Procurement exception is captured and visibly distinct.

## Inbound-reply and manual-evidence security tests

- `T-CONF-012` — Recorded inbound reply with matching request token plus `In-Reply-To`/references/message IDs, active eligible verified sender and configured explicit phrase is captured with provider timestamps/headers/authentication evidence and creates only the permitted review/action path.
- `T-CONF-013` — Unknown sender, matching domain but wrong identity, spoofed sender, invalid provider callback signature, replayed provider message fingerprint, unmatched/missing thread/token, forwarded content, auto-reply, read receipt and malformed MIME are quarantined/security-audited and never confirm.
- `T-CONF-014` — Ambiguous language (for example, “looks okay, discuss tomorrow”), parser low-confidence result or unavailable authentication evidence produces restricted manual-review queue with parsed interpretation; classifier/AI suggestion cannot autonomously confirm.
- `T-CONF-015` — Inbound raw MIME/reference, headers, attachment hashes and authentication evidence are retained/minimized according to classification/retention policy; unauthorized roles cannot retrieve them, attachments are scan-gated, and logs/error/API views redact restricted content.
- `T-CONF-016` — Manual historical evidence accepts only allowed scan-passed `.eml`/`.msg`/PDF/screenshot/export and mandatory sender/recipient/subject/message-ID/time/represented-decision/file-hash metadata. It records `MANUAL_EVIDENCE`, represented time and current recorded audit time separately and is never automatic verification.
- `T-CONF-017` — Manual evidence requires a second distinct authorized reviewer by default/configuration. Self-review, inactive/cross-tenant reviewer, missing hash/metadata, failed scan and disallowed historical scope are rejected; approval/rejection and reviewer reasoning are immutable/audited.
- `T-CONF-018` — **External acceptance:** authorized controlled mailbox webhook/subscription or bounded polling verifies provider signature/token, captures a real approved test reply/thread/authentication metadata, honors retention controls and routes real ambiguous/spoof scenarios safely. It is not passed with a fixture alone.

## Readiness, closure, reopen, and background-job tests

- `T-READY-001` — The five-pillar readiness view/report shows roster/allocation, attendance, plan/Linear, certification and confirmation/package/invoice-handoff status with source version/freshness, blocker severity, owner and CTA. It never reports ready based on UI cache alone.
- `T-READY-002` — Repeated/concurrent readiness evaluation with identical input manifest is idempotent; a changed attendance snapshot, plan revision, certification response, Linear status policy result or confirmation supersession produces a new result/invalidation correlation and does not erase earlier evidence.
- `T-CLOSE-001` — Close requires verified confirmation and configured F04 conditions, writes immutable manifest of all referenced IDs/hashes and locks covered evidence against generic update/delete. Unauthorized or incomplete close fails atomically without partial lock.
- `T-CLOSE-002` — Authorized reopen requires reason/category, impacted record set, package/invoice impact, recipients and risk statement. It appends lineage, marks prior confirmation/readiness handoff superseded, invalidates only affected downstream work and produces required selective recertification/reconfirmation tasks.
- `T-CLOSE-003` — Closed-month direct edit, generic SQL/service mutation and unapproved reopen are blocked. A legitimate correction never deletes historical confirmation/package references or backdates audit time.
- `T-JOB-001` — Reminder, outbox, inbound, readiness and retention jobs expose attempts/progress/checkpoint/next retry/error/correlation; crashes/restarts resume safely and bounded retries reach visible dead letter.
- `T-JOB-002` — Concurrent worker claims, duplicate scheduled trigger, provider callback replay and authorized replay yield exactly-once business effect through idempotency. Job/provider failure preserves explicit stale/blocked state and never fabricates approval.
- `T-OBS-001` — Metrics/logs/alerts capture outbox backlog/age, pending/invalid confirmation, job lag/dead letter, provider/scan/hash failure and cross-tenant/authentication anomaly with redaction. Authorized operations can correlate event → job → message/action without raw restricted payload exposure.

## API, persistence, authorization, and privacy tests

- `T-F04-DB-001` — Testcontainers PostgreSQL from empty applies all Flyway migrations; F04 foreign keys/checks/partial uniqueness/immutability reject duplicate current records, invalid state transition, cross-scope reference, consumed-token action, duplicate provider message and in-place evidence edits atomically.
- `T-F04-DB-002` — Canonical serialization has stable sorting/UTC normalization/hash algorithm version: equal logical input hashes identically; any included source/version/ordering-sensitive defined field change produces the expected new hash; hash comparisons are not treated as signer identity.
- `T-F04-SEC-001` — MockMvc/WebTestClient proves unauthenticated, disabled, wrong organization/engagement/project/deliverable, client/vendor separation-of-duty and unauthorized replay/manual-review/reopen access are denied safely; a valid scoped JWT succeeds. Client-provided organization, role, actor and recipient authority claims are ignored/rejected.
- `T-F04-SEC-002` — PostgreSQL least-privilege and Spring service authorization cover every F04 table/view/object-storage prefix and report query. React route/hidden control/deep link cannot confer access or leak record existence, restricted raw email/evidence or tenant metadata.
- `T-F04-SEC-003` — Input/output security covers XSS in comment, filename, URL, Linear/email text and template variables; SQL injection; CSRF; confirmation/webhook rate limits; MIME/content sniffing; signed-URL authorization/expiry; and formula-injection-safe exports. Responses contain action/correlation ID but no stack trace, secret, token, raw MIME or unnecessary PII.
- `T-F04-SEC-004` — Audit events are append-only and retain actor/authority/object/version/source/reason/correlation/policy/result/evidence references with sensitive redaction. Failed authorization, token replay, spoofed callback and restricted download are security audited; compensating correction appends rather than edits history.
- `T-F04-API-001` — Executable OpenAPI documents every F04 success and typed error/ETag/idempotency contract without secrets or restricted examples. `/v3/api-docs` and Swagger UI enforce the configured documentation audience.

## Frontend, Playwright, accessibility, and regression tests

- `T-F04-UI-001` — Playwright vendor journey saves a draft, surfaces every completeness/scan blocker, adds valid evidence/criteria/variance, displays Linear snapshot freshness, submits declaration, and then renders submission read-only with timeline/clarification response rather than editable overwrite.
- `T-F04-UI-002` — Playwright product-owner journey shows scoped inbox/aging, three-way baseline/vendor/decision context, criterion panel, evidence viewer, Linear plan/month-end/live labels, required decision fields and partial carry-forward. It verifies `Done` is not presented as acceptance.
- `T-F04-UI-003` — Playwright governance journey shows all five readiness pillars, source versions/freshness, blockers/owners/CTAs, exact confirmation recipient preview, request status/outbox failures, version lineage and reopen impact without F05 invoice implementation leakage.
- `T-F04-UI-004` — Playwright secure/in-app confirmation journey shows exact version/diff, authenticated eligibility check, comment validation, successful outcome/audit reference and replay/expired/forwarded/unauthorized safe-denial states. No token/secret/raw restricted evidence is rendered.
- `T-F04-UI-005` — Playwright inbound/manual review journey restricts raw message/evidence access, labels source/authentication confidence/manual status, requires reviewer decision/reason, and safely displays failure/quarantine rather than unsafe HTML/MIME rendering.
- `T-F04-UI-006` — Keyboard-only and screen-reader checks cover critical submission, certification, confirmation and reopen forms/dialogs: names/labels, focus order, error summary, non-color status, contrast, version/read-only state and mobile/tablet layout. Loading/empty/error/stale/permission-denied/version-conflict states are present.
- `T-F04-REG-001` — Regression runs F01/F02/F03 suites after F04 migrations and proves plan approval/commitment outbox, attendance snapshot/reopen and Linear snapshot/webhook behavior remain intact; F04 neither changes frozen F03 facts nor treats Linear delivery evidence as business acceptance.
- `T-F04-REG-002` — F05 contract regression consumes the versioned F04 readiness/confirmation/invalidation event using a stub consumer. It verifies F04 does not create package/invoice/procurement records while a later consumer receives enough immutable source/version/hash state to decide safely.

## Full-stack journeys and external-provider acceptance

- `E2E-03-F04` — Starting with an F03 frozen plan and fixture Linear snapshots, vendor submits every outcome/evidence; scoped product owner certifies one partial carry-forward; deterministic summary and certification notification are created. Assert F02 attendance remains independent and Linear `Done` never decided certification.
- `E2E-04` — With all readiness inputs present, governance creates a confirmation request; eligible product owner opens authenticated secure link, confirms exact version, quorum completes, outcome notification is sent via fake adapter and F05 readiness handoff becomes eligible. Replay/concurrent action yields no duplicate confirmation.
- `E2E-05` — A request becomes overdue; reminders/escalation fire; recorded valid reply is processed through restricted review/verification path, while ambiguous, spoofed, forwarded, receipt and silence inputs remain quarantined/blocked. No false approval occurs.
- `E2E-07-F04` — A post-confirmation attendance/certification correction triggers authorized reopen, preserves previous artifacts as superseded, invalidates readiness, creates a new summary/request with diff, and requires fresh confirmation before F05 handoff.
- `E2E-08-F04` — Historical June-onward vendor decision and original email evidence are imported through trusted fixture path. Represented date and current recorded date are distinct; missing approval creates clearly labelled retroactive request; current Linear state is never passed off as historical month-end evidence.
- `E2E-F04-PROVIDER-001` — **External acceptance:** approved tenant sender/mailbox and sandbox/live configuration execute certification and consolidated confirmation delivery, confirmed secure action, provider callback/bounce and archived metadata/retention checks. Verify no credentials or restricted raw content leak.
- `E2E-F04-PROVIDER-002` — **External acceptance:** controlled inbound mailbox receives an approved explicit reply from a verified eligible product owner and safely handles a real ambiguous/spoof/forwarded test reply. Provider-message dedupe, signature/authentication evidence, human-review routing and final business action are verified end-to-end.

## Completion rule

F04 is locally complete only when all non-external cases have automated evidence or an approved exception, F01–F03 regressions pass, and G0–G3 in `TASKS.md` pass. Production email/reply completion additionally requires the marked external acceptance cases and G4 approval; no fixture, transport receipt, silent timeout or UI-only state satisfies that gate.
