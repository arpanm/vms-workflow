# F07 — Final Open Issues

## Final integrated reconciliation — 2026-07-30

Open evidence: F07-T057 real 24-hour soak; a successful post-fix F07-T066/T067
restore/reconciliation; and F07-T070 execution against the exact clean
candidate. The DR and deterministic manifest implementations are present.
The final full Maven and browser regressions pass 347/347 and 292/292.
Production identity/provider/legal/capacity/DR/UAT approvals remain external
`NO-GO / ACTION_REQUIRED`.

## Local evidence pending

| ID | Priority | Required result |
|---|---:|---|
| F07-FINAL-001 | Closed locally | Definitive Maven R3 passes 73 unit + 217 integration (290/290), zero failures/errors/skips, BUILD SUCCESS in 03:21. |
| F07-FINAL-002 | Closed locally | F05 finance 4/4 and F06 migration 7/7 local-system E2E pass after F07 shared hardening. |
| F07-FINAL-003 | P1 | Local harness/schema checks pass; execute exact commit-bound supply-chain and a completed post-fix restore/rollback reconciliation. |
| F07-FINAL-004 | Closed locally | Final Terra review closed with no P0–P3 finding. |
| F07-FINAL-005 | Partially closed | T070 now has a fail-closed structured manifest lane and focused frontend/backend reproducibility evidence. The final release artifact/evidence bundle still requires execution and binding on the exact clean candidate commit. |
| F07-FINAL-006 | Closed locally | Corrected bounded harness/bootstrap paths pass in focused/static gates; original zero-test discovery and bootstrap timeout remain preserved in history. |
| F07-FINAL-007 | Closed locally; commit binding pending | The first exact supply-chain execution correctly failed. After security-fixed dependencies, a clean digest-pinned PostgreSQL 18.4 image, restricted pod/container contexts and fail-closed SPDX evaluation, the exact complete rerun passes all reports/artifacts/image with zero findings. Bind the same gate to the clean remediation commit. |
| F07-FINAL-008 | Closed locally | Final frontend passes 120/120, Maven passes 347/347, browser passes 292/292 and ordered F07 system passes 7/7 on V1–V45. |
| F07-FINAL-009 | P1 | Execute the local 24-hour soak (`F07-T057`/`F07-PERF-006`) and finish the post-fix controlled restore/reconciliation (`F07-T066`–`T067`). r1/r2 failed closed on `./` archive-root validation; that defect is fixed, but r3 was interrupted before a passing report. |
| F07-FINAL-010 | P1 | Run the five-dimension review against the exact candidate release commit. `review-evidence.json` currently closes only through `c2d8dfb`; the release gate rejects ancestor-only review evidence. |
| F07-FINAL-011 | P2 | Execute the T070 manifest on the clean candidate after its supply scan. The contract records frontend/backend checksums, SBOM references, fixed-epoch isolated rebuild equality, current/previous Flyway compatibility and readiness endpoints. Role guidance remains implemented; final verification remains part of F07-T081. |
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
