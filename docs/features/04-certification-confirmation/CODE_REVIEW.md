# F04 Code Review — Consolidated Decision

## 2026-07-30 withdrawal/artifact completion review

- Removed raw `File` values from query mutation state and removed invalid nested-form markup.
- Added scan-time SHA-256 verification plus executable/EICAR rejection.
- Added rollback object cleanup and pending temporary-file cleanup.
- Restricted database provider status to agree with the terminal scan result.
- Replaced the user-entered withdrawal reason in the domain event with a deterministic hash; the reason remains in the access-controlled audit record.

**Decision:** The reviewed local implementation satisfies the recorded G0–G3 provider-neutral scope, subject to a root-agent rerun. It is **not** full-stack or provider-accepted.

The review covered F04 Java/Spring services, V11/V12 PostgreSQL/Flyway migrations, React routes/contracts, authorization, state transitions, privacy, and prior-feature regression boundaries. Local fixes address the earlier backend, frontend, security, and independent-analysis findings; their detailed historical disposition is retained in [FIXES-BACKEND.md](FIXES-BACKEND.md), [FIXES-FRONTEND.md](FIXES-FRONTEND.md), [CODE_ANALYSIS.md](CODE_ANALYSIS.md), and [SECURITY_ANALYSIS.md](SECURITY_ANALYSIS.md).

## Review conclusions

- F02 snapshots and F03 frozen plan/baseline/Linear records are referenced, not rewritten. F04 does not create F05 invoice, package, or Procurement facts.
- Database scope, immutable lineage, SOD, request expiry, quorum conflict handling, correlation/redaction, and durable local job/outbox behavior have executable local coverage.
- The client sends intent, expected version, and idempotency data only; authority, recipient eligibility, scope, and transition decisions are server authoritative.
- Tokens, raw MIME, storage credentials, provider secrets, salary, rates, and payroll data are not exposed in F04 UI/API contracts.

## Remaining non-local gates

Approved sender/mailbox/callback security, live provider delivery and inbound verification, controlled storage/scanning, deployment database grants, production SSO/OTP/step-up and edge controls, and a non-intercepted browser/BFF/Java/PostgreSQL lane remain required. Those are acceptance/deployment gates, not evidence that local provider-neutral code has delivered live mail.

See the actionable ledger in [CODE_ISSUES.md](CODE_ISSUES.md) and the detailed prior review records: [backend](CODE_REVIEW-BACKEND.md), [frontend](CODE_REVIEW-FRONTEND.md), [security](SECURITY_ANALYSIS.md).
