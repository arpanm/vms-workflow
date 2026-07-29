# F07 — Remediation Record

This file maps preserved review findings to concrete changes. Verification is
qualified separately so that code presence is never mistaken for final release
evidence.

| Finding | Remediation | Focused evidence / remaining gate |
|---|---|---|
| F07-BE-001 migration bootstrap | Added an administrator bootstrap for a constrained migration login, removed application `SET ROLE`, secured both capability-owner and session-user defaults, and added `F07MigrationBootstrapIT`. | Included in the green 73-unit + 45-integration focused gate. |
| F07-BE-002 ownership lock | Excluded `flyway_schema_history` from ownership transfer and set a five-second migration lock timeout. | Original hang is preserved; bounded focused rerun passes. |
| F07-BE-003 runtime escalation | `DatabaseRoleGuard` now validates the exact runtime principal and rejects DDL/Flyway mutation and migration-owner membership. | Focused unit and Testcontainers gate passes. |
| F07-BE-004 retention lifecycle | Added schedule/run serialization, execution ownership, per-candidate `REQUIRES_NEW` effects, attempt transitions, retry deadline, dead letter and explicitly authorized recovery cycle. | Post-edit focused unit/integration scenarios pass. |
| F07-BE-005 chunked size bypass | Bounded pre-read/replay for non-multipart mutation bodies plus a 64 MiB configuration ceiling; multipart remains with the servlet resolver. | Declared, in-bound, chunked-oversize and multipart unit cases exist. |
| F07-BE-006 commercial key bypass | V22 normalizes JSON keys and blocks salary, rate, billing/cost/hourly and markup/commercial variants at the database boundary. | Nested/adversarial IT fixtures exist. |
| F07-BE-007 callback rate-limit abuse | Trust forwarded addresses only from exact configured proxies; resolve valid known connection IDs and collapse invalid/random IDs to a shared unknown bucket. | Trusted and untrusted proxy plus random-ID threshold tests exist. |
| F07-BE-008 null proof hash | Made candidate/proof hashes non-null and compute deterministic canonical SHA-256 when an upstream hash is absent. | Focused IT asserts non-null hashes and passes. |
| F07-BE-009 legal-hold bypass | Legacy finance hold release refuses active F07 holds and V22 adds a database guard trigger; releases must traverse the governed dual-control workflow. | Service/direct-writer coverage exists. |
| F07-OPS-001 evidence spoofing | Structured evidence repeats ID/kind/result/command/environment/duration, binds exact commit and clean provenance, and CI executes real suites before evaluation. | Local harness/static/schema gates pass; commit-bound workflow execution remains. |
| F07-OPS-002 migration declarations | Release preflight requires exact ancestor, loopback live DB and source-to-live Flyway checksum comparison plus checksummed rehearsal commands. | Schema/self-test implementation exists; controlled rehearsal remains required. |
| F07-OPS-003 backup substitution | AES encryption is paired with an independently keyed HMAC manifest containing backup UUID, source, time and commit; restore enforces freshness, commit and replay ledger. | Adversarial self-tests exist; actual controlled drill pending. |
| F07-OPS-004 path escape | Canonical-root and symlink checks, exclusive creation and safe tar member/link/type validation. | Harness self-test passes. |
| F07-OPS-005 image provenance | PostgreSQL Compose image is digest pinned and included in provenance. | Confirm exact artifact provenance at release. |
| F07-OPS-006 output collisions | Every evidence run uses a unique run ID and refuses overwrite. | Harness self-test passes. |
| F07-OPS-007 Semgrep pin | CI downloads Semgrep 1.135.0 wheel and validates its published SHA-256. | The transitive Python dependency closure remains explicitly outside the hash-lock claim. |
| F07-UI-001 accessibility/safe errors | Added safe generic error rendering, correlation support reference, skip link/focus, named navigation/progress, responsive mobile close and reduced-motion styling. | Failure history: 24/30 then isolated Firefox 6/6. Final complete browser matrix: 274/274. |
| F07-BE-010 delivery-worker IT isolation | Gave `DeliveryCommitmentOperationsWorkerIT` the dedicated `vms_workflow_delivery_commitment_worker` Testcontainers database so provider-effect counts cannot inherit another suite's state. | Preserved R2: 215/217 IT. Definitive R3: 73 unit + 217 IT, 290/290, BUILD SUCCESS in 03:21. |
| F07-SUPPLY-001 dependency/image/deployment/license findings | Upgraded PostgreSQL JDBC to 42.7.12 and both Jackson lines to security-fixed releases; replaced the vulnerable official PostgreSQL image with digest-pinned Chainguard PostgreSQL 18.4; added restricted pod/container security contexts; and made the license gate exclude only positively identified scanner-input manifests while parsing AND/OR/WITH SPDX choices fail closed. Exact allowed transitive licenses are enumerated and copyleft families remain denied except the explicit classpath exception. | Complete supply-chain rerun passes all scanner reports, both release artifacts and the image with zero findings. PostgreSQL V1–V33 bootstrap and Maven R4 290/290 pass on 18.4. Clean-commit provenance binding remains. |

## Final-system corrections

- Governance reads now use the governance authority while inbound ingest
  remains ingest-only; the exact system cases pass.
- HMAC test canonicalization now matches the wire timestamp, and E2E-07 sends
  the required version headers.
- Finance package pillar items preserve the immutable upstream source ID.
- Reopened confirmation requests retain the latest predecessor for
  `supersedes_id`.
- Certification handoff publication is deferred while an invalidation is
  active and is idempotently emitted when the last invalidation clears.
- Linear reconciliation timestamps now record the last terminal attempt while
  status/error continue to represent failure.
- Browser instrumentation now uses bounded migration POST/metadata/file polls
  and captures the exact multipart `File.name`. The isolated Firefox `_page`
  error did not reproduce.

These corrections are covered by the 7/7 F07 system, 4/4 finance system, 6/6
migration system and 274/274 browser reruns. Intermediate Maven R2 exposed two
delivery-worker provider-effect count failures; the dedicated-database fix is
verified by definitive Maven R3 at 290/290.

## Final fix gate

Before declaring local closure, run and preserve:

```bash
npm run sdlc:check
npm run typecheck
npm run lint
npm run test
npm run build
mvn -B -f backend/pom.xml verify
npm run e2e
npm run e2e:finance:system
npm run e2e:migration:system
npm run f07:self-test
npm run f07:ops:check
git diff --check
```

Supply-chain and controlled restore evidence must be executed against the exact
release artifacts. External gates remain `ACTION_REQUIRED`.
