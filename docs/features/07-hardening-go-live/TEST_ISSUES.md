# F07 — Test Issues

## Final integrated reconciliation — 2026-07-30

The remaining executable local evidence issues are F07-T057 (real 24-hour
soak), F07-T066/T067 (current recovery-boundary and DR drill), F07-T070
(artifact/provenance production), and the clean local commit. The full Maven
and browser failures remain recorded despite passing focused recovery. The
release-schema wrapper is environmentally unverified due to bind `EPERM` and a
467-second silent escalated retry.

| ID | Severity | State | Issue / closure condition |
|---|---:|---|---|
| F07-TEST-001 | P1 | FIXED / VERIFIED | V21 Flyway-history exclusion/lock timeout passes focused upgrade evidence and definitive Maven R3. |
| F07-TEST-002 | P1 | FIXED / VERIFIED AT RECORDED BASELINE | Split V22 ownership statements pass the then-current V1–V33 migration chain and definitive Maven R3. The current V1–V40 chain requires the new commit-bound full verification gate. |
| F07-TEST-003 | P2 | FIXED / VERIFIED | Preserved 24/30 plus isolated Firefox 6/6; final complete browser matrix passes 274/274. |
| F07-TEST-004 | P1 | LOCAL GATES PASS / PROVENANCE PENDING | Exact final frontend, Maven R3, browser and system gates pass; bind their outputs to the final local commit. |
| F07-TEST-005 | P1 | LOCAL NOT RUN + EXTERNAL ACTION_REQUIRED | Execute the repository's real local 24-hour soak report without shortening or synthesizing duration. Separately execute controlled production-like capacity/soak and obtain headroom approval. |
| F07-TEST-006 | P1 | ACTION_REQUIRED | Execute the authenticated deployed BFF/OIDC/provider system regression. |
| F07-TEST-007 | P1 | ACTION_REQUIRED | Perform manual keyboard and supported screen-reader acceptance with representative users. |
| F07-TEST-008 | P1 | ACTION_REQUIRED | Execute and approve production-like backup/restore/PITR/DR evidence. |
| F07-TEST-009 | P2 | FIXED / VERIFIED | Preserved zero-test discovery failure; final Vitest passes 24 files/92 tests and the harness runs explicitly. |
| F07-TEST-010 | P2 | FIXED / VERIFIED | Preserved brittle log-wait timeout; bounded startup passes focused evidence and definitive Maven R3. |
| F07-TEST-011 | P1 | FIXED / VERIFIED LOCALLY; COMMIT BINDING PENDING | The first supply-chain run failed as designed. The exact complete post-fix rerun passes all scanner reports, both release artifacts and the PostgreSQL image with zero findings; Maven R4 passes 290/290 on PostgreSQL 18.4. Repeat from the clean remediation commit for provenance binding. |
| F07-TEST-012 | CLOSED | E2E-01 completes governed roster setup and passes in the fresh V1–V42 ordered run. |
| F07-TEST-013 | P1 | CURRENT COMMIT REVIEW PENDING | Historical review evidence through `c2d8dfb` is valid history but cannot close a later release. The release gate now requires exact candidate-commit equality. |
| F07-TEST-014 | CLOSED | E2E-05 uses governed reopen, approval, recertification, inbound confirmation and invalidation resolution. V42 permits E2E-07's later governed correction after the prior append-only decision. Fresh ordered verification passes 7/7. |

Historical local execution remains green, while current V1–V40 regression,
soak/recovery, exact review and commit-bound provenance remain open. No case is
closed merely because its source exists.
