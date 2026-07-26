# F04 Code Issues — Consolidated Ledger

## Open acceptance and deployment controls

| ID | Status | Required before the claim it governs |
| --- | --- | --- |
| `F04-SEC-008` | External storage acceptance open | Approved ingestion/scanning, content controls, object storage, retention/legal hold, scoped audited downloads. |
| `F04-BE-016` | Deployment control open | Production database identities/grants/default-deny and restricted-reader proof. |
| `F04-SEC-009` | Deployment control open | Separate runtime/worker/audit identities, RLS or equivalent grants, prefix policy and retention/deletion proof. |
| `F04-SEC-012` | Platform hardening partly external | Production CORS/origin/CSRF architecture, edge headers, secret manager, pinned images and CI supply-chain/security gates. |
| `F04-G4` | External acceptance open | Approved sender/domain/mailbox, callback credentials, recipient/quorum/delegation/SLA policy, SSO/OTP/step-up, and sandbox/live delivery and inbound evidence. |
| `F04-SYSTEM` | Full-stack acceptance open | A non-intercepted browser/BFF/Java/PostgreSQL system run with controlled identity and provider configuration. |
| `F04-V13-DATA-001` | Conditional deployment data gate | Before applying V13 to a database that already accepted V11/V12 F04 traffic, audit/remediate any historic cross-month summary lineage, generic/out-of-scope invalidations, and rejected-reopen invalidations without a resolution. V13 protects new writes but does not rewrite existing facts. |

No open local P0/P1 product finding is recorded in the final remediation evidence. The prior findings are intentionally retained, not erased, in [CODE_ISSUES-BACKEND.md](CODE_ISSUES-BACKEND.md), [CODE_ISSUES-FRONTEND.md](CODE_ISSUES-FRONTEND.md), [CODE_ANALYSIS_ISSUES.md](CODE_ANALYSIS_ISSUES.md), and [SECURITY_ISSUES.md](SECURITY_ISSUES.md); [FIXES.md](FIXES.md) maps their resolution.

Do not close any row above from fixtures, a provider status, a receipt, silence, timeout, or intercepted Playwright run.

The independent V13 review records no open local P0/P1 in the five final P1
new-write paths. `F04-V13-DATA-001` is an upgrade/deployment gate: for a
database with pre-V13 F04 facts, it must be discharged before rollout rather
than treated as provider or consumer acceptance.
