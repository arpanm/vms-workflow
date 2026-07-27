# F05 — Operations runbook

## Safe response rules

Do not alter immutable evidence, bypass scan state, weaken scope checks or
manually label a blocked invoice ready. Capture correlation ID, actor, object
version, source hash and current state; use an authorized replay/correction
flow and preserve audit lineage.

| Signal | Immediate action | Follow-up |
| --- | --- | --- |
| Scan unavailable/quarantine | Keep document/package/export blocked; show actionable state. | Restore approved scanner, investigate content, upload immutable replacement if needed. |
| Hash/integrity failure | Deny download/package use and retain original bytes/metadata. | Investigate storage/renderer path, record security event, regenerate only from exact valid inputs. |
| F04 invalidation/reopen | Treat dependent F05 package/readiness/invoice as invalidated/superseded. | Route correction to owning F02–F04 workflow; create fresh F05 lineage after corrected handoff. |
| Export failure/dead letter | Inspect safe job error/correlation and provider configuration; do not expose output. | Use authorized replay after cause correction; verify one resulting artifact and audit event. |
| Version conflict/idempotency mismatch | Return current version/safe typed error; do not retry with changed payload under same key. | Refresh state; create a new intent only when payload changes. |
| Privacy/export incident | Revoke affected share/access where authorized, preserve audit evidence, stop further download. | Escalate under incident policy; verify report-mask/authority snapshot before replay. |

## External operational prerequisites

Before production acceptance configure and validate private versioned storage,
malware/quarantine callbacks, deterministic rendering, retention/legal-hold,
SSO/deployed grants, backup/restore, Procurement process and ERP/AP integration
if used. These are G4 external gates, not local configuration defaults.
