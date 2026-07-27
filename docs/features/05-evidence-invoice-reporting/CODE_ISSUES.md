# F05 — Code issue register

This is the consolidated register. It preserves history rather than erasing
findings after code changes.

## Historical issue sources

- Initial backend issues: [CODE_ISSUES-BACKEND.md](CODE_ISSUES-BACKEND.md)
- Initial frontend issues: [CODE_ISSUES-FRONTEND.md](CODE_ISSUES-FRONTEND.md)
- Intermediate post-fix register: [POST_FIX_ISSUES-BACKEND.md](POST_FIX_ISSUES-BACKEND.md)
- Latest static re-review: [FINAL_REVIEW-BACKEND.md](FINAL_REVIEW-BACKEND.md)

## Active validation items

| ID | Priority | Status | Required closure evidence |
| --- | --- | --- | --- |
| F05-VAL-001 | P0 | Fix present; **awaiting regression proof** | Natural blocked-readiness exception workflow, second-approver/SOD, expiry, mismatch and cross-tenant denial tests. |
| F05-VAL-002 | P1 | Fix/implementation requires **fresh verification** | Per-report projections/formulas, persisted authority-snapshot use and screen/export field-mask parity under multiple personas. |
| F05-VAL-003 | P2 | Open architecture follow-up | Database keyset or snapshot-bound cursor with concurrent mutation continuity proof; current opaque cursor is bounded but materializes scoped results. |
| F05-VAL-004 | P2 | Open local control follow-up | Authorized/audited legal-hold and scanner-transition workflow plus direct-SQL rejection tests. |
| F05-VAL-005 | P1 | Open test-depth gap | Independently committed concurrency/lease-loss/replay cases, not only transaction-rolled-back serial calls. |
| F05-VAL-006 | P2 | Open quality gate | Browser runtime, accessibility, performance, DR and full repository regression evidence. |

## External items (not local defects)

Approved object storage, malware/quarantine service, renderer hardening,
retention/legal-hold operations, deployed database grants, SSO policy,
Procurement process approval and ERP/AP integration are external G4 evidence.
They must stay explicitly external even after local tests pass.
