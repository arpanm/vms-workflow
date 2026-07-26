# F04 Backend Code Issues

**Disposition legend:** all findings are **Open**. P1 findings block local F04 release gates; P2 findings should be resolved before production readiness. Exact line references use the current working tree.

## P1 — Local release blockers

### F04-BE-001 — Confirmation and summary source scope can be bypassed at the database layer

- **Severity:** P1
- **Evidence:** [V11__certification_confirmation_local.sql](../../../backend/src/main/resources/db/migration/V11__certification_confirmation_local.sql) lines 323–350 and 369–428 use independent foreign keys for summary/request source IDs. The only F04 cross-scope trigger coverage is lines 791–895 (submission, outcome, criterion, certification). The confirmation transition guard at lines 954–968 does not constrain `attendance_snapshot_id`, `package_version_reference`, `supersedes_id`, `due_at`, hash metadata, or other scope-bound fields.
- **Impact:** A direct database write or future service can bind a month’s confirmation/summary to a different month, plan, attendance snapshot, policy, or engagement and can alter hash-bound request scope after issuance. That defeats tenant/object lineage, exact-scope confirmation, and immutable evidence requirements.
- **Recommendation:** Add engagement/month lineage columns or composite foreign keys, plus reviewed trigger checks for every child/source relationship. Freeze all scope/hash fields after draft creation; make any correction a new superseding record. Add negative PostgreSQL tests for cross-engagement/month references and post-issue scope mutation.
- **Disposition:** Open — G1/G2 blocker.

### F04-BE-002 — Invalidations cannot be cleared, while closure and reopen records are not immutable

- **Severity:** P1
- **Evidence:** `certification_invalidations.status` supports `ACTIVE`, `CLEARED`, and `SUPERSEDED` at [V11](../../../backend/src/main/resources/db/migration/V11__certification_confirmation_local.sql) lines 711–726, but the append-only trigger at lines 1192–1194 rejects every update. Readiness permanently counts active rows at [CertificationReadinessService.java](../../../backend/src/main/java/com/vms/workflow/application/CertificationReadinessService.java) lines 287–290. `month_closures` and `month_reopen_requests` are defined at V11 lines 663–709 but have no guard in the trigger list at lines 1138–1206.
- **Impact:** A correction/rejection/reopen permanently blocks readiness, with no represented resolution lineage; conversely closure/reopen evidence may be directly updated/deleted. This breaks selective invalidation/reconfirmation and closure immutability.
- **Recommendation:** Introduce a reviewed resolution/supersession transition that is audit-linked (or append a separate resolution fact and derive effective state), protect closure/reopen rows with transition/delete guards, and implement only authorized resolution/approval services. Test direct update/delete attempts and a full reopen→recertify→reconfirm path.
- **Disposition:** Open — G2/G3 blocker.

### F04-BE-003 — Separation of duties is declared but not enforced

- **Severity:** P1
- **Evidence:** Policy creation hard-codes `separationOfDutiesRequired=true` at [CertificationWorkflowService.java](../../../backend/src/main/java/com/vms/workflow/application/CertificationWorkflowService.java) lines 1482–1490, but no decision path consumes it. `requireItemDecision` checks client party/permission/designated owner at [CertificationAuthorizationService.java](../../../backend/src/main/java/com/vms/workflow/security/CertificationAuthorizationService.java) lines 87–117 and never rejects a subject who is also the submission creator, vendor owner, vendor manager, or service identity.
- **Impact:** A principal with active client and vendor authorities can self-certify its own vendor delivery. This violates PRD 08 §3 and T-CERT-001, and is a material approval-integrity failure.
- **Recommendation:** Resolve the submission/vendor-owner/vendor-party identities server-side and reject overlap when the captured policy requires SOD; require an explicit, separately authorized and audited exception path. Add multi-membership and service-identity tests.
- **Disposition:** Open — G1 blocker.

### F04-BE-004 — Confirmation authorization is not bound to the eligible project assignment

- **Severity:** P1
- **Evidence:** `requireConfirmationAction` proves only that the actor occurs in the request snapshot, then accepts either engagement permission or *any* project-scoped `certification.confirmation.act` permission at [CertificationAuthorizationService.java](../../../backend/src/main/java/com/vms/workflow/security/CertificationAuthorizationService.java) lines 140–164. It does not compare that permission scope with `confirmation_request_eligibility.project_id`.
- **Impact:** A user eligible for a Project B request but holding the action permission only in Project A can confirm Project B. Captured eligibility does not replace current object/project scope validation.
- **Recommendation:** Join the eligibility row to the actor’s active project/engagement assignment and require scope equality (or a valid engagement-wide authority); preserve the captured authority snapshot for audit only. Add wrong-project, revoked, delegated, and multi-project tests.
- **Disposition:** Open — G2 blocker.

### F04-BE-005 — Request expiry and captured token/due policy are bypassed

- **Severity:** P1
- **Evidence:** `act` accepts every `AWAITING_RESPONSE` request without comparing `due_at` to the current clock at [BusinessConfirmationService.java](../../../backend/src/main/java/com/vms/workflow/application/BusinessConfirmationService.java) lines 257–334. `PolicyRow` loads `token_ttl_seconds` and `confirmation_due_seconds` at lines 598–613, but request creation only imposes a five-minute arbitrary minimum at lines 101–105 and issues tokens using mutable process configuration at lines 181–191.
- **Impact:** An in-app action remains possible after the confirmation deadline, and a policy change can change token expiration for a request that is supposed to be governed by its captured policy. Neither `EXPIRED` nor a blocking no-response state is reliably reached.
- **Recommendation:** Enforce due time atomically in the action update, transition/record `EXPIRED` through a durable idempotent job, and calculate due/token expiry from the captured policy row. Test expired/replayed/in-app-after-due behavior.
- **Disposition:** Open — G2 blocker.

### F04-BE-006 — Multi-party quorum sends an outcome notification before quorum, then suppresses the terminal outcome

- **Severity:** P1
- **Evidence:** Every action calls `enqueueNotification` at [BusinessConfirmationService.java](../../../backend/src/main/java/com/vms/workflow/application/BusinessConfirmationService.java) lines 359–363, including an `AWAITING_QUORUM` confirmation. The outbox idempotency key ignores action/result state and is only `eventType:objectId:objectVersion` at [CertificationWorkflowService.java](../../../backend/src/main/java/com/vms/workflow/application/CertificationWorkflowService.java) lines 1793–1806.
- **Impact:** For `ALL`/`N_OF_M`, the first confirmation can publish the “outcome” message before a business outcome exists. When the final confirmation reaches quorum, its message conflicts and is discarded, so the verified terminal outcome is not reliably notified.
- **Recommendation:** Create separate, idempotent action-recorded and terminal-outcome events; enqueue the outcome only on the guarded state transition. Include resulting state/quorum contribution in the outbox idempotency key and add concurrent quorum tests.
- **Disposition:** Open — G2 blocker.

### F04-BE-007 — Local outbox, retry, reminder, and secure-link delivery vertical is absent

- **Severity:** P1
- **Evidence:** The only email adapter implementation always returns `NOT_CONFIGURED` at [ProviderNeutralCertificationEmailAdapter.java](../../../backend/src/main/java/com/vms/workflow/application/ProviderNeutralCertificationEmailAdapter.java) lines 21–25; no production source invokes `CertificationEmailAdapter.send`. Request creation intentionally discards plaintext secure tokens at [BusinessConfirmationService.java](../../../backend/src/main/java/com/vms/workflow/application/BusinessConfirmationService.java) lines 181–195. There are no scheduled worker, retry/dead-letter, reminder, or expiry classes/endpoints.
- **Impact:** Even the required local fake/recorded delivery path cannot deliver an action link or process attempts/retries/reminders. The persisted outbox is a write-only record, so T-MSG-003–006, T-CONF-004 secure-link workflow, and T-JOB-001/002 cannot pass.
- **Recommendation:** Implement a server-only fake adapter and durable worker claim/retry/dead-letter/replay contracts; pass plaintext token only through a transaction-safe, non-persisted dispatch handoff; add idempotent reminder/expiry jobs. Keep live-provider work behind G4 configuration.
- **Disposition:** Open — G2 blocker; live provider remains an external gate.

### F04-BE-008 — Required F04 lifecycle, inbound/manual review, and closure APIs/services are missing

- **Severity:** P1
- **Evidence:** The controller’s implemented routes end with a reopen *request* at [CertificationController.java](../../../backend/src/main/java/com/vms/workflow/api/CertificationController.java) lines 214–230. No controller/service operation exists for close, reopen approval/rejection, invalidation resolution, inbound message ingestion/review, manual evidence upload/review, notification history/replay, or secure token preview/send.
- **Impact:** The migration tables do not make the workflow executable. PRD 08/09 correction, historical evidence, anti-spoof review, close/reopen lineage, and operator controls cannot be performed through authorized APIs.
- **Recommendation:** Implement the missing typed service/controller operations with scoped authority, expected version/idempotency, immutable audit facts, and safe 404 denial; do not expose raw MIME/artifact bytes. Add the associated MockMvc/Testcontainers tests.
- **Disposition:** Open — G2/G3 blocker.

### F04-BE-009 — Submission validation does not enforce evidence policy or recorded authorized exceptions

- **Severity:** P1
- **Evidence:** `submissionBlockers` at [CertificationWorkflowService.java](../../../backend/src/main/java/com/vms/workflow/application/CertificationWorkflowService.java) lines 1107–1176 checks declaration, item/criterion count, variance/carry-forward, and plan revision, but never checks evidence expectations, evidence links, scan exceptions, or policy-authorized exceptions. `insertEvidenceLinks` only validates supplied artifacts at lines 1078–1104.
- **Impact:** A vendor can submit a complete-looking deliverable with zero evidence even when the frozen deliverable/policy requires evidence. The mandatory evidence gate in T-DEL-007 is bypassed.
- **Recommendation:** Interpret the captured evidence policy/frozen expectation per deliverable; require scan-passed evidence or a separately authorized immutable exception with reason/authority. Test required/optional/quarantined/exception cases.
- **Disposition:** Open — G1 blocker.

### F04-BE-010 — Readiness cannot represent authorized attendance exceptions and does not persist all material inputs

- **Severity:** P1
- **Evidence:** Attendance is marked blocked whenever a closed snapshot is absent at [CertificationReadinessService.java](../../../backend/src/main/java/com/vms/workflow/application/CertificationReadinessService.java) lines 63–69; no authorized disclosed-exception source is queried. The immutable readiness input manifest at lines 337–351 omits roster/Linear counts, pending revision, confirmer eligibility, recipient completeness, and other values that determine the returned result.
- **Impact:** Permitted attendance exceptions cannot progress; conversely a changed material readiness condition can reuse the old `input_hash`/run rather than append a new evidence result. This fails T-CONF-001 and T-READY-002.
- **Recommendation:** Add a typed captured exception reference and policy/authority check; include every source ID/version/hash/status that influences readiness in canonical input. Serialize through the configured mapper, lock/dedupe concurrent runs, and test source changes create a new run.
- **Disposition:** Open — G2/G3 blocker.

### F04-BE-011 — F05 handoff is a no-op and is reported from configuration rather than a durable contract event

- **Severity:** P1
- **Evidence:** The publisher implementation returns `NOT_CONFIGURED` without persistence at [ProviderNeutralF05CertificationReadinessPublisher.java](../../../backend/src/main/java/com/vms/workflow/application/ProviderNeutralF05CertificationReadinessPublisher.java) lines 21–27. `BusinessConfirmationService` ignores the result at lines 379–385. Readiness derives eligibility solely from configuration at [CertificationReadinessService.java](../../../backend/src/main/java/com/vms/workflow/application/CertificationReadinessService.java) lines 151–176.
- **Impact:** No versioned `certification.confirmation.readiness` fact is durably available to a stub/F05 consumer, and a configured-looking status can imply handoff eligibility despite no event having been published.
- **Recommendation:** Persist a versioned F05 contract/outbox record in the same transaction as the confirmation/readiness transition; record publication result and make readiness report that durable fact. Keep transport delivery behind the interface/feature flag.
- **Disposition:** Open — G3 blocker.

### F04-BE-012 — No F04 automated tests exist

- **Severity:** P1
- **Evidence:** `backend/src/test/java` contains only `ApiTenantSecurityIT`, `DeliveryApprovalConcurrencyIT`, `DeliveryLinearIT`, `WorkforceAttendanceIT`, and `JwtDecoderIT`; `rg -n "Certification|confirmation|F04" backend/src/test` finds no F04 test. `mvn clean verify` passes 49 legacy tests and V11 migration from empty PostgreSQL only.
- **Impact:** The listed acceptance cases have no executable proof, including database invariant bypass, tenant/SOD authorization, concurrent actions, token replay/expiry, no-silence behavior, outbox behavior, and F01–F03 regression through F04 paths.
- **Recommendation:** Add a dedicated F04 Testcontainers suite and MockMvc tests covering every non-external case in `TEST_CASES.md`, then retain the legacy suites as regressions. Treat only marked provider cases as external acceptance.
- **Disposition:** Open — G1/G2/G3 blocker.

## P2 — Important hardening and contract issues

### F04-BE-013 — Policy versioning is structurally blocked

- **Severity:** P2
- **Evidence:** The policy schema models `ACTIVE`/`SUPERSEDED` versions at [V11](../../../backend/src/main/resources/db/migration/V11__certification_confirmation_local.sql) lines 10–44, but lines 1138–1140 attach `reject_immutable_change` to every update. `ensurePolicy` only creates version 1 when none exists at [CertificationWorkflowService.java](../../../backend/src/main/java/com/vms/workflow/application/CertificationWorkflowService.java) lines 1469–1521.
- **Impact:** Engagement policy cannot be superseded or configured/versioned as required; its captured defaults are effectively permanent.
- **Recommendation:** Permit only audited `ACTIVE → SUPERSEDED` state transition, then insert a new immutable version; expose an authorized policy-management operation.
- **Disposition:** Open.

### F04-BE-014 — Recipient deduplication does not deduplicate across To and CC, and templates are hard-coded

- **Severity:** P2
- **Evidence:** Recipient lists are deduplicated separately at [CertificationWorkflowService.java](../../../backend/src/main/java/com/vms/workflow/application/CertificationWorkflowService.java) lines 1822–1827 and 1859–1871. Message content and template version `1` are hard-coded at lines 1772–1806.
- **Impact:** One address can receive duplicate To/CC delivery; no immutable, publish-governed template/version or attachment manifest exists beyond the hard-coded skeleton.
- **Recommendation:** Deduplicate over the combined channel set while retaining all role reasons, define a versioned template contract with required variables/plain text/HTML/attachment hashes, and archive the rendered version.
- **Disposition:** Open.

### F04-BE-015 — API errors and success responses lack the required correlation contract

- **Severity:** P2
- **Evidence:** `ApiExceptionHandler` emits status/type/instance and occasionally code/current version at [ApiExceptionHandler.java](../../../backend/src/main/java/com/vms/workflow/api/ApiExceptionHandler.java) lines 21–62, but no correlation ID. F04 service methods create correlation IDs internally, while DTO responses do not consistently expose them.
- **Impact:** Operators cannot reliably correlate an API request with the audit/domain/outbox fact, contrary to the F04 API contract and operational requirements.
- **Recommendation:** Generate/request a correlation ID at the boundary, return it on all success/error responses, propagate it through audit/domain/outbox, and document it in executable OpenAPI.
- **Disposition:** Open.

### F04-BE-016 — Restricted email/contact persistence has no F04 database access-control evidence

- **Severity:** P2
- **Evidence:** V11 stores raw `verified_email` at lines 356–367 and raw sender/recipient/subject fields for manual evidence at lines 589–612, but no F04 grants, row-scope policy, classification-to-role mapping, or restricted access/audit path is introduced. The implemented controller has no restricted review endpoint, but that does not protect database readers.
- **Impact:** Restricted personal contact and manual-email metadata may be available to ordinary database/application roles without the required least-privilege proof.
- **Recommendation:** Classify these columns, add PostgreSQL role/grant and service authorization tests, hash/minimize where raw values are unnecessary, and access-log restricted retrieval.
- **Disposition:** Open.

## External acceptance gates (not coded defects)

- Approved email provider/sender domain/dedicated mailbox, callback signing, and real sandbox/live send.
- Controlled mailbox webhook/subscription or bounded polling authority, retention approval, and real reply/spoof/ambiguity exercises.
- Tenant-approved recipient groups, delegates, quorum/SLA/escalation/retention policies, and SSO/OTP/MFA/step-up policy.
- F05 consumer deployment and its external operational acceptance.

These remain blocked by G4. They must not be marked complete from a fixture, delivery receipt, UI state, timeout, or silence.

---

## Post-fix independent review addendum — 2026-07-26

History above is preserved. The remediation changes the disposition below; it
does not erase the original finding evidence.

| Finding | Post-fix disposition | Exact remaining condition |
| --- | --- | --- |
| `F04-BE-001` | **Partial — Open, P1 local blocker** | V12 scopes current source fields, but not `monthly_certification_summaries.supersedes_id`; `requestReopen()` also accepts arbitrary impacted UUIDs without proving typed same-month ownership. |
| `F04-BE-002` | **Partial — Open, P1 local blocker** | A rejected reopen leaves its request-created invalidations effective `ACTIVE`; `CLEARED` accepts any later confirmation rather than a correction tied to the exact invalidated fact. |
| `F04-BE-003` | **Partial — Open, P1 local blocker** | Item-decision SOD only compares the actor with the submission author, and confirmation action has no vendor-affiliation/SOD check. A dual vendor/client user can act on another vendor user's submission. |
| `F04-BE-008` | **Partial — Open, P1 local blocker** | Safe inbound/manual recording and review exist, but review never creates a source-attributed business confirmation action or participates in quorum. Mailbox transport remains external; the missing reviewed-evidence-to-action transition is local. |
| `F04-BE-011` | **Partial — Open, P1 local blocker** | Reopen approval invalidates the handoff but leaves its durable publish job claimable. The F05 worker does not revalidate confirmation/effective-handoff state before publishing. |
| `F04-BE-004`–`007`, `F04-BE-009`–`015` | **Resolved locally, subject to the P1 lifecycle blockers above** | Independent code trace found no additional direct P0/P1 bypass in their stated local scope. |
| `F04-BE-016` | **Open deployment control** | Production database identities/grants and restricted-reader proof remain outside this repository. |

See `POST_FIX_REVIEW.md` for exact evidence, test gaps, and release verdict.
