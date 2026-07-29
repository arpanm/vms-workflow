# F04 UI Documentation

## Routes and users

| Route | User flow |
| --- | --- |
| `/certification` | Server-scoped cross-month work inbox for vendor drafts, assigned certification decisions, summaries, and direct month access. |
| `/certification/$monthId` | Vendor draft: outcomes, criterion evidence references, declaration, save/submit, lock, and clarification timeline. |
| `/certification/$monthId/review` | Assigned product-owner inbox: compare frozen baseline, vendor result and evidence; certify/clarify/carry-forward; generate explicit summary. |
| `/confirmation` | Cross-month confirmation work plus durable notification/reminder/expiry/F05-handoff health. Authorized operators can replay only failed configured notification work after entering a reason; the retained intent is exact-month-version bound. |
| `/confirmation/$monthId` | Five-pillar readiness, blockers/owners/CTAs, exact recipient/quorum/due preview, request lineage, inbound safe metadata, notification status, and reopen impact. |
| `/confirmation/requests/$requestId` | Eligible in-app response: exact scope/diff, action validation, audit history, terminal/replay/expiry state. |

## How the workflow behaves

1. Vendor saves a versioned draft and submits only the returned exact version once completeness blockers clear; a submitted version becomes read-only.
2. An assigned product owner records explicit item/criterion decisions. Linear `Done`, percentages, messages, and receipts remain supporting evidence, never a decision.
3. Governance uses the server’s five-pillar readiness and exact recipient/quorum snapshot to create a request. The browser converts due-time display while preserving the UTC instant.
4. An eligible authenticated user confirms or requests correction/rejection with required rationale. A replay shows the prior result; expiry, silence, ambiguous reply, and transport status cannot confirm.
5. Reopen records reason, impact, lineage, and selective downstream invalidation. F04 exposes F05 handoff status only; it has no package/invoice controls.

All material views render loading/empty/error/stale/locked/conflict/safe-denial states, correlation references, non-colour status text, accessible linked validation summaries, and responsive layouts. The browser never renders or stores plaintext tokens, token hashes, raw MIME, provider credentials, signed storage URLs, payroll, rates, or salary data.

The UI uses Java APIs but the current browser evidence intercepts them. Secure-link exchange, live email/mailbox, artifact viewing/scanning, and F05 execution stay externally gated. See [CODEGEN-FRONTEND.md](CODEGEN-FRONTEND.md), [FIXES-FRONTEND.md](FIXES-FRONTEND.md), and [TEST_AUTOMATION.md](TEST_AUTOMATION.md).
