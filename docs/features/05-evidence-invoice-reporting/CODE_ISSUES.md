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
| F05-VAL-001 | P0 | Resolved locally; exact recovery 1/1 | A real quarantined artifact produces a persisted `INVOICE_DOCUMENT` blocker. Policy versions an explicit business-rule exception allowlist, while `INVOICE_DOCUMENT` and `PACKAGE_MANIFEST` remain non-waivable. The test appends a new exact-bound lineage and preserves SOD, expiry, revoked-authority and cross-tenant denials. |
| F05-VAL-002 | P1 | Existing local coverage; fresh verification pending | Per-report projections/formula escaping, persisted authority-snapshot rejection and restricted field suppression are implemented in focused tests. |
| F05-VAL-003 | P2 | Existing local coverage; fresh verification pending | Signed bounded snapshot/keyset continuity is covered by `FinancePaginationIT`; very-large-scale capacity remains a performance gate. |
| F05-VAL-004 | P2 | Existing local coverage; fresh verification pending | Authorized legal-hold/scanner auditing and direct-SQL rejection are covered by `FinanceArtifactGovernanceIT` and `FinanceDatabaseControlsIT`. |
| F05-VAL-005 | P1 | Export/package/retention/share resolved; invoice/review breadth open | Export completion requires a live claimant lease; package generation, retention disposal and overlapping share grants have committed competing-caller coverage. Independent invoice upload/submit and review-decision races remain depth work. |
| F05-VAL-006 | P2 | Partially evidenced | Axe/keyboard/tablet passed 3/3 and finance system passed 4/4. Generated-file depth, performance/scale, controlled DR and G4 remain open. |

## External items (not local defects)

Approved object storage, malware/quarantine service, renderer hardening,
retention/legal-hold operations, deployed database grants, SSO policy,
Procurement process approval and ERP/AP integration are external G4 evidence.
They must stay explicitly external even after local tests pass.

## 2026-07-30 completion-audit disposition

- **Resolved locally — dashboard API/UI contract:** Java previously returned
  `metricId/displayValue/unavailable` while React consumed
  `metricCode/value/availability`. Java now publishes the executable contract,
  React retains backward compatibility, and focused Java/React tests pass.
- **Resolved locally — truncated dashboard aggregates:** metrics and queue
  counts no longer derive from the first 50 tower rows. PostgreSQL aggregates
  the complete authorized engagement set; a 55-row integration fixture passes.
- **Locally hardened — F05-VAL-001/F05-TEST-002:** the scanner creates a
  natural document blocker, document/package integrity rules are now
  non-waivable, and the governed exception regression uses a policy-declared
  business readiness rule rather than an invented rule code. Exact SOD,
  expiry, mismatch, revoked-authority and cross-tenant checks remain covered.
  The exact Finance recovery passed **1/1**.
- **Still open — F05-VAL-005 depth:** independently committed competing
  invoice/review mutations remain absent. Two-worker committed export-claim,
  package, and overlapping-share races are green; live-lease fencing and
  expired-claim recovery coverage were exercised; broader mutation concurrency
  remains open.

The integrated Maven result remains 340 executed with 2 failures and 1 error;
the **1/1** Finance recovery is a separate row, not a full-green claim.

## V45 completion-audit disposition

- **Resolved — retention implementation:** V45 and
  `RetentionPrivacyService` reuse the organization-scoped schedule,
  dry-run/explicit-execution and authority-evidence model. The finance disposer
  accepts only an eligible approved candidate, rechecks legal hold/references,
  couples local byte deletion to immutable transition/proof/audit/event/outbox
  evidence and rejects direct bypass. No duration is seeded.
- **Resolved — policy-driven upload UI:** invoice reads publish the effective
  policy and React submits its classification/retention values.
- **Resolved — share concurrency:** overlapping grants produce one committed
  share/event and a typed `PACKAGE_SHARE_WINDOW_CONFLICT` loser.
- **Still open depth:** independent invoice upload/submit and competing review
  decisions; non-transactional provider-delete retry/finalization; scale/DR
  and external G4.
