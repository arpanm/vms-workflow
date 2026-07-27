# F05 Post-fix Backend Issues

**Gate:** **NO-GO** — P0-01 remains open. The post-fix implementation addressed most of the prior findings, but the exception route is still unreachable from `EVIDENCE_PENDING`; the remaining findings below also require remediation and regression/security automation.

| ID | Priority | Status | Required remediation | Required evidence |
|---|---|---|---|---|
| P0-01 | P0 | **Open** | `acceptException` still calls `requireReviewState`, which rejects `EVIDENCE_PENDING`; call the existing `requireExceptionState` instead. Retain the exact lineage, policy-controlled second approver, derived exception readiness run, and `EXCEPTION_ACCEPTED → SUBMITTED_TO_PROCUREMENT` transition. | E2E blocked rule → exception → submission/review; expired/second-approver/cross-tenant denial tests. |
| P1-01 | P1 | **Fixed locally** | Exact current F04 readiness-run equality is enforced before package generation. | Wrong UUID/cross-month/stale UUID rejection tests. |
| P1-02 | P1 | **Fixed locally** | Source-only F04 appendix entries are explicitly disclosed as immutable object/version/hash references; available invoice evidence retains its private scan-passed artifact. | Manifest completeness and source-hash change/supersession tests. |
| P1-03 | P1 | **Fixed locally** | Effective F05 policy now controls upload MIME/size/classification/retention, readiness mandatory rules and exception second approval; package/readiness retain its version label. | Policy effective-date and changed-policy behavior tests. |
| P1-04 | P1 | **Open** | Report IDs have permission checks and export authority snapshots, but the worker renders the same unmasked control-tower rows for every report ID and does not consume the stored authority snapshot. Implement report-specific, persona-scoped queries/masking and metric formulas; bind rendering to the stored snapshot. | Export/screen field-parity and cross-persona/non-disclosure tests. |
| P1-05 | P1 | **Fixed locally** | Finance organization is now included in authorization scope, engagement discovery and export actor selection. | Separate Finance AP organization access/SOD integration test. |
| P2-01 | P2 | **Partially fixed** | Control tower and reports now accept opaque stable-key cursors, but paging still materializes the complete scoped list and then slices it in the controller; it is not a database keyset/snapshot cursor and cannot provide a stable concurrent snapshot. | Concurrent insert/update cursor continuity tests. |
| P2-02 | P2 | **Fixed locally** | An authorized, audited replay endpoint resets only failed/dead-letter exports; bounded automatic retry remains explicit. | Worker failure, retry exhaustion, replay permission/idempotency tests. |
| P2-03 | P2 | **Partially fixed** | The migration now constrains scan-state changes and protects artifact fields, but there is no authorized/audited legal-hold transition and permitted scan transitions are not independently audited. | Direct SQL mutation rejection plus scan/legal-hold transition audit tests. |
| P2-04 | P2 | **Fixed locally** | The local scanner defaults to disabled and returns a non-passing configuration result; generated/uploaded content therefore fails closed unless explicitly enabled. | Production profile fail-closed scan/download test. |
| P2-05 | P2 | **Fixed locally** | Database-backed per-identity/client/org finance mutation/export/download buckets return 429 and produce security events; datastore failure is fail-closed. | Rate-limit and audit/security-event tests. |
| P3-01 | P3 | **Fixed locally** | Package and export integrity validation now uses byte-level SHA-256 for every format. | JSON/PDF/CSV/XLSX package download integrity tests. |
| P3-02 | P3 | **Fixed locally** | Query response content and lineage are returned to the assigned owner/Procurement and explicitly restricted for other viewers. | Query owner/Procurement/viewer DTO contract tests. |

## Counts

- Open P0: 1
- Open P1: 1
- Open/partial P2: 2
- Fixed locally: 9
- Total prior issues: 13
