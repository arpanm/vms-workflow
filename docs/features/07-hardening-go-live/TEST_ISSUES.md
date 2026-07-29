# F07 — Test Issues

| ID | Severity | State | Issue / closure condition |
|---|---:|---|---|
| F07-TEST-001 | P1 | FIXED / VERIFIED | V21 Flyway-history exclusion/lock timeout passes focused upgrade evidence and definitive Maven R3. |
| F07-TEST-002 | P1 | FIXED / VERIFIED | Split V22 ownership statements pass the production V1–V33 migration chain and definitive Maven R3. |
| F07-TEST-003 | P2 | FIXED / VERIFIED | Preserved 24/30 plus isolated Firefox 6/6; final complete browser matrix passes 274/274. |
| F07-TEST-004 | P1 | LOCAL GATES PASS / PROVENANCE PENDING | Exact final frontend, Maven R3, browser and system gates pass; bind their outputs to the final local commit. |
| F07-TEST-005 | P1 | ACTION_REQUIRED | Execute controlled production-like capacity and a 24-hour-or-longer soak; approve headroom. |
| F07-TEST-006 | P1 | ACTION_REQUIRED | Execute the authenticated deployed BFF/OIDC/provider system regression. |
| F07-TEST-007 | P1 | ACTION_REQUIRED | Perform manual keyboard and supported screen-reader acceptance with representative users. |
| F07-TEST-008 | P1 | ACTION_REQUIRED | Execute and approve production-like backup/restore/PITR/DR evidence. |
| F07-TEST-009 | P2 | FIXED / VERIFIED | Preserved zero-test discovery failure; final Vitest passes 24 files/92 tests and the harness runs explicitly. |
| F07-TEST-010 | P2 | FIXED / VERIFIED | Preserved brittle log-wait timeout; bounded startup passes focused evidence and definitive Maven R3. |
| F07-TEST-011 | P1 | FIXED / VERIFIED LOCALLY; COMMIT BINDING PENDING | The first supply-chain run failed as designed. The exact complete post-fix rerun passes all scanner reports, both release artifacts and the PostgreSQL image with zero findings; Maven R4 passes 290/290 on PostgreSQL 18.4. Repeat from the clean remediation commit for provenance binding. |

Final local execution is green; no case was closed merely because its source
exists. Production-like/external cases and commit-bound provenance remain open.
