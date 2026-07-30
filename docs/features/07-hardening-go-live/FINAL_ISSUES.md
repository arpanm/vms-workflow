# F07 — Final Open Issues

## Final integrated reconciliation — 2026-07-30

Open local evidence: F07-T057 real 24-hour soak; F07-T066/T067 current
recovery-boundary and DR drill; F07-T070 artifact/provenance production. The
implementation is committed locally as `6f3e9c9` and was not pushed. Full Maven/browser failures remain open aggregate evidence even
though exact recoveries pass. Production identity/provider/legal/capacity/DR/
UAT approvals remain external `NO-GO / ACTION_REQUIRED`.

## Local evidence pending

| ID | Priority | Required result |
|---|---:|---|
| F07-FINAL-001 | Closed locally | Definitive Maven R3 passes 73 unit + 217 integration (290/290), zero failures/errors/skips, BUILD SUCCESS in 03:21. |
| F07-FINAL-002 | Closed locally | F05 finance 4/4 and F06 migration 6/6 local-system E2E pass after F07 shared hardening. |
| F07-FINAL-003 | P1 | Local harness/schema checks pass; execute exact commit-bound supply-chain and controlled restore/rollback evidence. |
| F07-FINAL-004 | Closed locally | Final Terra review closed with no P0–P3 finding. |
| F07-FINAL-005 | Partially closed | Implementation and reconciled documentation are committed locally at `6f3e9c9`; the T070 release artifact/evidence bundle still requires exact commit binding. |
| F07-FINAL-006 | Closed locally | Corrected bounded harness/bootstrap paths pass in focused/static gates; original zero-test discovery and bootstrap timeout remain preserved in history. |
| F07-FINAL-007 | Closed locally; commit binding pending | The first exact supply-chain execution correctly failed. After security-fixed dependencies, a clean digest-pinned PostgreSQL 18.4 image, restricted pod/container contexts and fail-closed SPDX evaluation, the exact complete rerun passes all reports/artifacts/image with zero findings. Bind the same gate to the clean remediation commit. |
| F07-FINAL-008 | P1 | Run the remaining complete frontend/Maven/browser regression on the V1–V42 candidate. The ordered F07 system lane is current at 7/7; preserved R3/R4 and browser results remain historical. |
| F07-FINAL-009 | P1 | Execute the local 24-hour soak (`F07-T057`/`F07-PERF-006`) and current controlled recovery-boundary/restore evidence (`F07-T066`–`T067`). These are locally executable gates distinct from production-like approval. |
| F07-FINAL-010 | P1 | Run the five-dimension review against the exact candidate release commit. `review-evidence.json` currently closes only through `c2d8dfb`; the release gate rejects ancestor-only review evidence. |
| F07-FINAL-011 | P2 | Produce current deployable artifacts/provenance (`F07-T070`). Role-specific contextual/user guidance (`F07-T079`) is implemented in the application and `docs/ROLE_GUIDES_AND_SUPPORT.md`; final verification remains part of F07-T081. |
| F07-FINAL-012 | CLOSED | Governed roster and confirmation setup now passes. V42 fixes effective-pending reopen enforcement without removing the single-pending guard. Fresh V1–V42 ordered system verification passes 7/7. |

The ordered F07 system lane is closed locally at 7/7, the capacity lane at 73
unit + 2 capacity tests, and the full browser matrix at 274/274 after its
preserved 268/274 and exact 7/7 reruns. None of these closes the external
production gates below.

The intermediate Maven R2 run completed in 39:23 under the corrected one-hour watchdog.
Two approximately 16–17 minute thread-starvation/clock-leap pauses under Docker
load are preserved as host evidence. Its 215/217 IT result was traced to the
worker IT sharing test-database state; the dedicated database correction passes
in R3.

## External `ACTION_REQUIRED`

| Gate | Required owner/evidence |
|---|---|
| Identity and service accounts | Security/platform: OIDC/BFF, MFA/step-up, rotation, logout/revocation and human-only/service-account policy. |
| Secrets/providers | Platform/provider: secret manager, scanner, storage, email, Linear/greytHR and durable callbacks. |
| Legal/privacy/retention | Legal/privacy/data: approved fields/notices/sharing, schedule periods, hold authority and recipients. |
| Observability/support | Operations/security: metrics/logs/traces/paging delivery, named on-call/escalation and support readiness. |
| Capacity/reliability | Platform/data: production-like load, at least 24-hour soak, headroom/autoscaling and 99.9% target approval. |
| Accessibility | Product/accessibility: representative-user keyboard and supported screen-reader acceptance. |
| Backup/DR | Data/platform: backup/PITR/residency/encryption, approved RPO/RTO, production-like restore and quarterly schedule. |
| Cutover | Release/business: named approvers, UAT, training, provider reconciliation, Procurement acceptance, canary and rollback drill. |

These issues are not optional feature reductions. They are evidence that can
only be supplied by the real deployment and accountable owners.
