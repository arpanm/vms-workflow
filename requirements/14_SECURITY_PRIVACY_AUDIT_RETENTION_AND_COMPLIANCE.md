# 14 — Security, Privacy, Audit, Retention and Compliance

**Version:** 1.0
**Status:** Mandatory release specification
**Related:** 03, 09, 10, 13, 16, 17

---

## 1. Objective

Protect employee, business and procurement evidence; enforce organization boundaries; make approval/evidence actions attributable; and prevent the current prototype's anonymous-access pattern from reaching production.

Security controls are release gates, not future enhancements.

---

## 2. Data classification

| Class | Examples | Default handling |
|---|---|---|
| `PUBLIC` | none expected in core app | explicit approval before publication |
| `INTERNAL` | project names, non-sensitive statuses | authenticated, scoped |
| `CONFIDENTIAL` | employee attendance, leave, deliverables, Linear metadata, invoices | strict organization/engagement scope, encrypted, audited |
| `RESTRICTED` | integration secrets, personal contact data, raw email/MIME, privileged audit/security logs | server/admin only, minimal retention, enhanced access log |

Salary/payroll/rate/markup data is prohibited, not merely restricted.

Every table/field/file bucket has a documented classification and allowed roles.

---

## 3. Threat model priorities

- cross-tenant data exposure through missing RLS or insecure joins/views;
- privilege escalation through client-side role manipulation;
- stolen/forwarded confirmation link or spoofed email reply;
- tampering with closed-month evidence or package files;
- replay/duplicate webhook, punch, approval or import processing;
- leaked greytHR/Linear/email credentials;
- malicious file upload, CSV formula injection or unsafe document rendering;
- excessive employee monitoring/collection beyond attendance purpose;
- admin abuse and unaudited manual correction;
- stale/revoked external integration being presented as current;
- injection/XSS through comments, filenames, Linear text or email content;
- backup/export leakage;
- denial of service through large imports/webhooks/exports;
- orphaned access after employee/user exit.

---

## 4. Authentication controls

- Production requires authenticated sessions; no anonymous business data access.
- Prefer enterprise federation; external invites use verified email and secure passwordless/OTP/MFA policy.
- MFA/step-up authentication for privileged administration, manual confirmation evidence, reopen and Procurement exception where supported.
- Session expiry, refresh and revocation follow organization policy.
- Disable user immediately invalidates protected operations; revoke sessions for high-risk exits.
- OAuth callback state/PKCE validation for Linear.
- Service accounts use non-interactive credentials, minimal permissions and rotation.
- No shared human accounts.

---

## 5. Authorization and RLS

### 5.1 Mandatory rules

- Enable RLS on every user-accessible table and Storage bucket.
- Policies require authenticated role plus active membership/scope.
- Remove all current `anon all` policies before loading workforce data.
- Browser client uses only publishable/anon key with RLS; service-role key is server-only.
- Authorization helper functions use fixed empty/explicit `search_path`, minimal ownership/grants and stable semantics.
- Views/functions are reviewed for RLS bypass; exports run through authorized server code.
- Child-object access derives from parent engagement/organization, not client-supplied organization ID.
- Write policy checks both old and new row where applicable.
- Archived/disabled membership cannot access history unless a separate auditor role remains.

### 5.2 Test matrix

For every table/API/storage prefix test:

- allowed role/scope read/write;
- same organization but wrong engagement/project;
- different organization;
- self versus other employee;
- disabled/expired membership;
- direct REST/Supabase call bypassing UI;
- service account minimal access;
- export/report/view access.

Any missing policy fails CI/release.

---

## 6. Secrets and integration security

- Store greytHR, Linear and email secrets in deployment secret manager/Supabase Vault-equivalent; ordinary database rows contain only secret references.
- Never expose provider tokens in browser/network responses.
- Redact headers, tokens, passwords and sensitive payloads from logs/errors.
- Rotate secrets and webhook signing secrets with overlap/cutover plan.
- Validate provider TLS and approved hostnames; prevent arbitrary URL/server-side request forgery through allowlisted connection hosts.
- Linear webhook verifies exact raw body/signature/timestamp/replay window.
- Email webhook/provider callbacks verify signature/token where provider supports it.
- greytHR tenant URL is validated against approved domain format and cannot redirect credentials to arbitrary host.
- Revoke/disconnect removes future access but preserves historical normalized evidence.

---

## 7. Data minimization and privacy

- Collect only attendance/evidence data needed for governance.
- Geolocation, selfie, biometric or continuous tracking is off by default and requires separate policy/legal approval; this PRD does not require it.
- Do not infer productivity from source-code commits, keystrokes or continuous surveillance.
- Personal email/mobile are restricted and excluded from evidence exports by default.
- Leave medical/other sensitive attachments have narrower access than attendance summary.
- Product owners see workforce summaries/detail only as required by engagement policy; they do not gain general HR access.
- Evidence packages prefer employee number/name/status and exclude unnecessary contact/identity data.
- Data-subject correction requests use controlled versioning, not silent deletion of business records where retention is required.
- Privacy notice explains purpose, source, retention, recipients and correction route.

---

## 8. File/upload security

- Private buckets; signed short-lived URLs.
- Allowlist file types and size; validate MIME by content, not extension only.
- Malware scan/quarantine before viewing/package use.
- Sanitize filenames and never execute active content/macros.
- Render HTML/email/Markdown safely; escape scripts and external tracking.
- CSV/XLSX export/import protection against formula injection.
- Image/document metadata stripping where required.
- Hash each file on ingestion and after generated output.
- Scan status, uploader/source, classification and retention are mandatory metadata.
- Package generation ignores quarantined/failed files and reports blocker.

---

## 9. Confirmation and anti-spoof controls

### Secure link

- high-entropy opaque token; store only hash;
- single-use, expiry and explicit request/version binding;
- authenticated identity/OTP and eligible-role check;
- replay protection and CSRF protection;
- forwarded link does not transfer authority.

### Email reply

- match provider message ID/thread/reference/request token;
- verify sender against active eligible identity;
- preserve available authentication results and headers;
- ambiguous content requires human review;
- attachments scanned;
- no automatic confirmation from delivery/read receipts, auto-replies or forwarded content;
- manual evidence requires second review as configured.

---

## 10. Audit trail

### 10.1 Audited actions

At minimum:

- login/invite/role/delegation/access change;
- organization/engagement/project/config changes;
- employee/allocation/calendar/leave balance changes;
- punches, leave, regularization and admin corrections;
- source mapping/sync/import/reconciliation decisions;
- plan/deliverable revisions/approvals;
- delivery submission/certification;
- emails/confirmation actions;
- package generation/download/supersession;
- invoice/procurement/payment state;
- month close/reopen;
- export/download and privileged support access;
- failed authorization, webhook signature and security events.

### 10.2 Audit event fields

- immutable ID/time;
- actor type/ID/organization/role/authority snapshot;
- action and object ID/type/version;
- engagement/month;
- old/new value diff with sensitive-field redaction;
- reason/comment/source;
- request/session/IP/device/provider metadata as permitted;
- correlation/causation IDs;
- policy/version and result;
- evidence/file/message refs.

### 10.3 Immutability

- Normal application roles cannot update/delete audit events.
- Append-only database permissions and periodic integrity checks.
- Optional chained hashes/immutable archive for high-value evidence.
- Corrections append compensating events.
- Audit export is read-only, signed/hashed and access logged.

---

## 11. Retention and legal hold

Retention is configurable by record class and approved by Reliance/ArrowFoundry policy/legal teams. Do not hard-code a statutory period without formal instruction.

Suggested policy categories:

- account/security logs;
- attendance/leave evidence;
- plan/certification/confirmation/invoice packages;
- raw provider payloads/staging data;
- temporary exports/signed links;
- failed/quarantined uploads;
- backups.

Rules:

- approved evidence/package retained for contractual/audit period;
- raw staging minimized and deleted after reconciliation unless required;
- exports expire quickly;
- legal hold overrides deletion and is audited;
- deletion job creates proof/report and skips referenced/held records;
- closed-month evidence is archived rather than altered.

---

## 12. Encryption and transport

- TLS for all external/internal traffic.
- Provider credentials encrypted in secret store.
- Database/storage encryption at rest through platform controls.
- Additional application-level encryption for highly restricted fields if risk assessment requires.
- No sensitive data in URLs/query strings where it may enter logs/history.
- Secure cookie settings/headers and content-security policy.

---

## 13. Application security

- Validate/sanitize all input server-side.
- Parameterized database access; no dynamic SQL from user values.
- Output encode comments, Linear content and email renderings.
- CSRF protections for cookie-authenticated mutations.
- Rate limit login, check-in/out, invite, confirmation, webhook and export endpoints.
- File download authorization on every request.
- Dependency, SAST, secret and container/function scanning in CI.
- No `.env` with real credentials committed; repository currently contains an `.env` file and must be reviewed/sanitized/rotated before production.
- Security headers and secure CORS allowlist.
- Error responses avoid stack traces/provider secrets/PII.

---

## 14. Backup, restore and continuity

- Automated database and Storage backup consistent with platform plan.
- Define and test RPO/RTO before go-live; initial target in PRD 16.
- Point-in-time restore capability where available.
- Backup encryption/access restriction.
- Quarterly restore drill on staging with evidence checksums.
- Integration outages degrade with stale warnings/queues; core evidence remains available.
- Package manifest/checksum used after restore to detect corruption.

---

## 15. Security monitoring and incident response

Alert on:

- repeated failed logins/authorization;
- cross-tenant access attempts;
- privilege/role changes;
- unusual mass exports/downloads;
- webhook signature/replay failures;
- secret/auth failures or integration revocation;
- audit write failure;
- package checksum mismatch;
- malware upload;
- backup/job failure;
- high-volume check-in/import abuse.

Runbooks include containment, access revocation, evidence preservation, stakeholder notification, root-cause and corrective action. Security events are separate from ordinary business notifications.

---

## 16. Compliance and records governance

- Follow applicable India data protection, employment, contract, tax and records requirements plus Reliance/ArrowFoundry policies.
- Legal/procurement must approve attendance evidence wording, deemed-acceptance policy (default disabled), retention and cross-organization sharing.
- Maintain purpose and data-owner register.
- Evidence actor identity/approval source must be explainable to auditors.
- AI, if later used to classify email or detect anomalies, remains advisory for approvals and is logged/evaluated.

This document is a product/security specification, not legal advice.

---

## 17. Acceptance criteria

- No production `anon` policy can select/insert/update/delete business tables.
- Forged organization ID in a request cannot cross tenant through API or report view.
- Disabled user/session cannot continue protected actions.
- Linear/greytHR/email secrets do not appear in client bundle, database dump of ordinary tables or logs.
- Invalid/replayed Linear webhook cannot update issue state.
- Unauthorized/forwarded confirmation link cannot confirm.
- Quarantined file cannot be viewed or included in package.
- Audit rows cannot be modified by business/admin UI roles.
- Closed package checksum detects altered file/manifest.
- CSV export neutralizes formula injection.
- Mass export and privileged role changes create security/audit alerts.
- Restore drill reproduces current evidence manifests/checksums.
- Geolocation/biometric tracking is absent unless separately enabled by approved policy.
