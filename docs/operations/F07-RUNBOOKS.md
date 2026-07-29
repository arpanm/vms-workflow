# F07 Operational Runbooks

These runbooks use the Java 25/Spring Boot/PostgreSQL architecture. A UI
control never grants authority, provider outage never implies approval, and a
rollback preserves new data/events. Replace role names with named people and
tested contact paths before go-live. Commands that mutate data or infrastructure
remain platform-specific and require an approved target and change record.

<a id="rb-01-user-access-sso-or-invite-failure"></a>
## RB-01 User access, SSO or invite failure

**Detection:** Login/invite failure, disabled-membership denial, issuer/audience/JWKS readiness code, or denial burst.
**Owner:** Identity operations owner.
**Diagnostics:** Record correlation ID and safe denial code; verify issuer/audience/JWKS reference state, identity and membership dates, scope, role version and clock without copying tokens.
**Safe actions:** Keep access denied; correct approved identity/configuration through the authoritative admin path; expire stale sessions and retest one synthetic identity.
**Escalation:** Security for signature/claim anomalies; workforce owner for membership data; provider owner for OIDC outage.
**Communications:** Give the user the correlation ID and support route; never request a password or token.
**Rollback or containment:** Disable affected login route or cohort and preserve denial/security events; do not weaken validation.
**Closure evidence:** Cause, configuration/role version, actor role, before/after safe code, test identity result and event links.

<a id="rb-02-employee-unable-to-check-in-or-out"></a>
## RB-02 Employee unable to check in or out

**Detection:** Punch error/latency alert, employee report, duplicate rate, or acknowledged-event discrepancy.
**Owner:** Workforce operations owner.
**Diagnostics:** Correlate request, employee scope, source authority, effective calendar and idempotency result; check database/queue readiness without exposing PII.
**Safe actions:** Preserve the attempt, offer the authorized regularization path, retry only with the original idempotency key, and reconcile durable facts.
**Escalation:** P1 for acknowledged loss or many employees; security for cross-scope symptoms; data owner for calculation defects.
**Communications:** State whether the attempt is pending, recorded or needs regularization; never infer a punch.
**Rollback or containment:** Disable the affected cohort flag, preserve events and use the approved contingency capture.
**Closure evidence:** Correlation/idempotency IDs, counts, reconciliation, latency, affected window, notification and owner sign-off.

<a id="rb-03-attendance-calculation-or-source-conflict"></a>
## RB-03 Attendance calculation or source conflict

**Detection:** Reconciliation difference, overlapping source, invalid calendar, close blocker or employee dispute.
**Owner:** Workforce data owner.
**Diagnostics:** Compare effective-dated source, raw immutable facts, represented/recorded time, calendar, leave and calculation version.
**Safe actions:** Freeze close, mark the difference unresolved, append an authorized correction/regularization and regenerate only affected snapshots.
**Escalation:** Governance for closed months; integration owner for provider data; privacy owner for a data-subject correction.
**Communications:** Explain the source/version and review path without disclosing another employee.
**Rollback or containment:** Pause the source switch or affected flag; retain both histories and never overwrite evidence.
**Closure evidence:** Difference report, authority decision, correction lineage, recalculation counts, downstream invalidation and notification.

<a id="rb-04-leave-accrual-or-reconciliation-failure"></a>
## RB-04 Leave accrual or reconciliation failure

**Detection:** Negative/impossible balance, accrual job failure, source mismatch or close blocker.
**Owner:** Workforce data owner.
**Diagnostics:** Check policy version/effective dates, opening balance, approved requests, source freshness, job checkpoint and duplicate effects.
**Safe actions:** Stop affected accrual posting, retain calculated and provider values, append correction after approval and rerun idempotently.
**Escalation:** HR policy owner for interpretation; integration owner for stale provider state; governance for closed evidence.
**Communications:** Mark balance as under review and avoid claiming fresh provider truth.
**Rollback or containment:** Disable affected policy/cohort and use last verified value labelled stale.
**Closure evidence:** Policy/source versions, balance reconciliation, corrected entries, job replay result and stakeholder approval.

<a id="rb-05-greythr-auth-sync-or-schema-outage"></a>
## RB-05 greytHR auth, sync or schema outage

**Detection:** Authentication/schema error, freshness SLO breach, retry exhaustion or reconciliation drift.
**Owner:** Integration operations owner.
**Diagnostics:** Inspect redacted capability, credential-version status, checkpoint, schema contract, retry/dead-letter state and last successful timestamp.
**Safe actions:** Mark data stale, stop unsafe writes, use bounded retry, preserve checkpoint and route authorized work to the approved fallback.
**Escalation:** Provider owner at retry budget; workforce owner before source-authority change; security on credential anomaly.
**Communications:** Display provider, last-known timestamp, affected workflow and fallback; never display stale data as current.
**Rollback or containment:** Disable integration flag, retain queued events and keep the previous verified source policy.
**Closure evidence:** Provider incident, freshness gap, retry/replay counts, reconciliation and approved resume.

<a id="rb-06-linear-oauth-webhook-or-reconciliation-outage"></a>
## RB-06 Linear OAuth, webhook or reconciliation outage

**Detection:** OAuth revoke, signature/replay rejection, webhook backlog, stale issue state or reconciliation mismatch.
**Owner:** Delivery integration owner.
**Diagnostics:** Check capability/key version, signature result, event ID, checkpoint, retry/dead-letter state and stored snapshot time without logging payloads/tokens.
**Safe actions:** Preserve signed metadata, mark Linear state stale, bound retries, authorize replay by event ID and use recorded internal delivery evidence.
**Escalation:** Security on signature bursts; provider owner on outage; product owner before delivery decision.
**Communications:** State stale timestamp and unavailable actions; provider outage is not delivery approval.
**Rollback or containment:** Disable webhook/sync flag and retain all received events for reconciliation.
**Closure evidence:** Incident window, event/replay counts, signature summary, snapshot reconciliation and resume approval.

<a id="rb-07-email-send-bounce-or-reply-ingestion-failure"></a>
## RB-07 Email send, bounce or reply ingestion failure

**Detection:** Send failure, bounce, inbound signature error, queue age/dead letter or silence threshold.
**Owner:** Confirmation operations owner.
**Diagnostics:** Correlate message/request/version, recipient eligibility, provider status, signature metadata, attempts and dead-letter state; never expose raw mail.
**Safe actions:** Keep confirmation pending, use bounded retry, notify eligible users in-app and replay only an authorized immutable message.
**Escalation:** Security for spoof/signature anomalies; product owner for deadline impact; provider owner after retry budget.
**Communications:** State delivery status and alternate authenticated path; delivery/read receipt never confirms.
**Rollback or containment:** Disable email ingestion flag, retain messages quarantined and use in-app confirmation.
**Closure evidence:** Message/request IDs, sanitized provider result, retry count, final disposition and notification.

<a id="rb-08-confirmation-spoof-or-ambiguity-review"></a>
## RB-08 Confirmation spoof or ambiguity review

**Detection:** Sender/thread/signature mismatch, auto-reply, ambiguous language, replay or forwarded token.
**Owner:** Confirmation governance owner.
**Diagnostics:** Review restricted metadata, eligible authority, exact request/version, signature/replay result and attachment scan state with dual control.
**Safe actions:** Quarantine, keep outcome pending, request a fresh authenticated response and record a non-confirming review decision.
**Escalation:** Security for spoof/replay; legal/privacy for disputed evidence; product owner for a new request.
**Communications:** Do not quote restricted mail broadly; provide the authenticated correction route.
**Rollback or containment:** Revoke affected token/key version and disable inbound ingestion without altering source facts.
**Closure evidence:** Reviewer roles, safe reason, request/version, retained metadata hash, disposition and notifications.

<a id="rb-09-import-failure-resume-or-rollback"></a>
## RB-09 Import failure, resume or rollback

**Detection:** Validation/commit failure, stalled checkpoint, dead letter, reconciliation mismatch or partial domain effect.
**Owner:** Migration operations owner.
**Diagnostics:** Check source/template hashes, policy version, checkpoint, error codes, sign-offs, domain-effect counts and downstream consumption.
**Safe actions:** Quarantine failed input, resume from durable checkpoint, reprocess explicit rows and use compensating actions only when authorized/unconsumed.
**Escalation:** Data owner for mismatch; governance for consumed/closed effects; security for malicious files.
**Communications:** Publish counts and unresolved rows, not raw restricted values.
**Rollback or containment:** Pause commit flag; preserve imported facts/provenance and never delete accepted evidence.
**Closure evidence:** Source/template hashes, before/after counts, checkpoint/retry history, compensation lineage and approvals.

<a id="rb-10-package-generation-or-hash-mismatch"></a>
## RB-10 Package generation or hash mismatch

**Detection:** Generation failure, scan failure, manifest/content checksum mismatch or non-deterministic repeat.
**Owner:** Evidence operations owner.
**Diagnostics:** Isolate package version, input IDs/hashes, build metadata, storage/scan state and access audit; do not distribute suspect content.
**Safe actions:** Quarantine package, block download/invoice readiness, preserve bytes/manifest and regenerate a superseding version from verified inputs.
**Escalation:** P0 security/data incident for any altered closed package; Procurement for submitted evidence impact.
**Communications:** Notify authorized recipients of withdrawal/supersession without exposing content.
**Rollback or containment:** Disable generation/share flags and revoke capabilities; never replace the prior package in place.
**Closure evidence:** Both manifests/hashes, root cause, access list, superseding lineage, scan result and recipient acknowledgement.

<a id="rb-11-month-reopen-and-downstream-invalidation"></a>
## RB-11 Month reopen and downstream invalidation

**Detection:** Authorized reopen request, stale readiness, missing invalidation or lineage discrepancy.
**Owner:** Governance owner.
**Diagnostics:** Resolve closed version, authority/SoD, reason, impacted attendance/certification/confirmation/package/invoice and notifications.
**Safe actions:** Require approved reopen, append new lineage, invalidate only affected downstream readiness and regenerate/reconfirm exact versions.
**Escalation:** Procurement for submitted invoices; security for unauthorized mutation; data owner for scope uncertainty.
**Communications:** Show impact and superseded/current versions to every affected role.
**Rollback or containment:** Pause affected workflow and flags; preserve every prior closed artifact.
**Closure evidence:** Request/approval actors, impact manifest, invalidation events, replacements, reconciliations and notices.

<a id="rb-12-invoice-or-procurement-change-request"></a>
## RB-12 Invoice or Procurement change request

**Detection:** Procurement query/rejection/exception, invoice replacement request or readiness regression.
**Owner:** Procurement operations owner.
**Diagnostics:** Check invoice/package/version, scan state, authority/SoD, confirmation/readiness, prior decisions and access history.
**Safe actions:** Keep immutable versions, record query/decision, block payment readiness and require an authorized superseding invoice/package.
**Escalation:** Governance for source correction/reopen; security for scope/download issue; legal for exception policy.
**Communications:** State exact version, required correction and deadline; do not email restricted artifacts.
**Rollback or containment:** Revoke shares and disable affected payment progression while preserving history.
**Closure evidence:** Decision actor/authority, old/new hashes, readiness recalculation, exception evidence and notifications.

<a id="rb-13-backup-restore-and-disaster-recovery"></a>
## RB-13 Backup, restore and disaster recovery

**Detection:** Missed backup, encryption/checksum failure, restore discrepancy, database/storage outage or declared disaster.
**Owner:** Data operations owner.
**Diagnostics:** Verify target identity, backup/inventory manifest, encryption reference, Flyway history, row/object counts, provider freshness and recovery boundary.
**Safe actions:** Isolate an empty `_f07_drill` target, verify checksums before restore, restore without clean/drop, reconcile and revalidate access before traffic.
**Escalation:** Incident commander for production impact; security for integrity/access; platform owner for PITR/region failover.
**Communications:** Publish measured status/RPO/RTO and discrepancies; never claim the initial target from a local logical drill.
**Rollback or containment:** Keep traffic closed, preserve source/backup, and use the last verified recovery point; never restore to an unresolved target.
**Closure evidence:** Signed `T-DR-001` report, duration, manifests, Flyway/count/hash comparison, access smoke, discrepancies and named approval.

<a id="rb-14-security-incident-and-evidence-preservation"></a>
## RB-14 Security incident and evidence preservation

**Detection:** Cross-tenant attempt, privilege anomaly, secret exposure, malware, mass export, audit failure or checksum alert.
**Owner:** Security incident commander.
**Diagnostics:** Assign correlation/incident ID, scope affected identities/objects/key versions/events and preserve redacted logs/audit/hash evidence.
**Safe actions:** Contain traffic/flags, revoke access/key versions, quarantine artifacts and preserve chain of custody; use approved clean credentials.
**Escalation:** Legal/privacy/data/platform/business owners under the incident matrix; provider if their boundary is involved.
**Communications:** Use need-to-know templates, approved breach path and verified facts only.
**Rollback or containment:** Revert application artifact when compatible, never erase audit/new events, and restore only from verified clean evidence.
**Closure evidence:** Timeline, scope, custody hashes, containment/rotation, affected-user decision, root cause, corrective actions and approvals.

<a id="rb-15-data-correction-or-privacy-request"></a>
## RB-15 Data correction or privacy request

**Detection:** Authenticated correction/access request, privacy complaint, retention conflict or legal hold.
**Owner:** Privacy records owner.
**Diagnostics:** Verify requester/authority, purpose, record classification, tenant/object scope, retention/hold and downstream immutable evidence.
**Safe actions:** Minimize export, mask unauthorized fields, append correction/version, protect held/referenced records and audit every download/action.
**Escalation:** Legal for statutory interpretation; governance for closed evidence; security for unauthorized access.
**Communications:** Use the approved response channel/timeline and disclose only authorized scoped data.
**Rollback or containment:** Suspend deletion/share capabilities for disputed scope; never silently edit/delete immutable evidence.
**Closure evidence:** Verified authority, search scope, export hash/access log, correction lineage, hold/retention decision and requester response.

