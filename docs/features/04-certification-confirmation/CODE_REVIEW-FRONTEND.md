# F04 frontend code review

**Review date:** 2026-07-26
**Scope:** the React/TanStack implementation in `src/features/certification/`, the six F04 routes, generated route tree, current F01–F03 frontend conventions, and the available Java DTO draft. Product code was not changed.

## Verdict

**Blocked.** Do not claim the F04 frontend locally complete yet. The product-owner review route is nested below a parent that does not render an outlet, the secured F04 API routes are not implemented in the available backend, and there is no F04 unit, integration, or Playwright coverage. See blockers `F04-FE-001` through `F04-FE-003` in [CODE_ISSUES-FRONTEND.md](CODE_ISSUES-FRONTEND.md).

## What is sound

- The UI treats the Java API as authoritative. Permission flags only affect affordances; mutations carry an expected version and the browser never supplies organization, role, recipient eligibility, quorum, or business-state authority.
- Linear state, percentage, mail transport, reminders, silence, and elapsed due date are explicitly presented as non-decisive evidence. This follows F04-TASK-009, -014 through -016, and `T-F04-UI-002`/`T-CONF-011`.
- The F04 browser contract contains no plaintext confirmation token, token hash, MIME, signed object URL, storage credential, or email-provider credential. Safe evidence is rendered as metadata, not an artifact body.
- The governance and confirmation screens distinguish business state from transport state, show request lineage and a server-returned diff, and prevent browser-side invoice/package creation.
- `rg` found no runtime Lovable or Supabase import/dependency in the application or F04 code. Historical references in `requirements/` are documentation only.
- The route and sidebar additions preserve the existing F01–F03 approach of letting the server make authorization decisions. The unconditional navigation links are therefore a usability concern at most, not an authority grant.

## Browser/API contract assessment

The TypeScript contract intentionally keeps restricted values out of the browser, which is correct. It is not, however, sufficient to implement all local F04 journeys:

- `CertificationDtos.java` currently supplies DTOs and authorization support, but no F04 controller/mapping exists for the routes used by `certificationApi`. This is a browser-contract blocker, not a reason to invent client-side state (`F04-FE-002`).
- The Java `MonthCertificationView` has no inbound/manual-review items and the DTO set has no reviewer decision request. The optional TypeScript `inboundReviews` field is therefore unreachable from the documented backend draft (`F04-FE-007`).
- There is no safe artifact selection/view/upload contract. The UI should not create one locally; instead it needs a server-provided list/selector and an authorized, short-lived view action. The existing criterion-level input cannot be populated at all (`F04-FE-006`).
- The contract does not provide assignment/age data for the promised product-owner inbox, nor source-specific names/checksums for a human-readable exact confirmation scope (`F04-FE-008`, `F04-FE-009`).

## Security and state-management assessment

- Good: React escaping is used throughout; no raw HTML/MIME is injected; IDs are URL-encoded; safe links are limited to absolute in-app paths; and high-impact action buttons use server-returned eligibility and version values.
- Needs correction: a new idempotency key is generated per invocation, so a user retry after a lost response is not a replay of the same intent (`F04-FE-004`).
- Needs correction: vendor “Submit exact version” submits the last saved server draft even when the visible form is dirty (`F04-FE-005`).
- Needs correction: form state is seeded once from React Query data and not reconciled after a save/refetch/version conflict, permitting stale fields to be sent with a newer expected version (`F04-FE-010`).
- Defense in depth is missing: F04 renders the arbitrary API error message after a generic safe-denial message. This undermines the intended redaction boundary if a server error regresses (`F04-FE-012`).

## Accessibility and resiliency assessment

Loading, empty, permission-denied, locked, stale, query-error, and version-conflict states have clear non-color text. Inputs generally have labels and the critical action/status regions use semantic status or alert roles.

The required-field behaviour remains incomplete: several actions are silently disabled when a required comment/rationale/scope is absent, without a linked inline error or error summary explaining what needs attention. This fails the actionable keyboard/screen-reader error-feedback part of `T-F04-UI-006` (`F04-FE-011`).

## External gates — not frontend defects

These remain intentionally unresolved and must not be represented as locally passed:

- **G4 mail provider/inbound mailbox acceptance:** no approved sender, dedicated monitored mailbox, callback signing material, recipient/quorum/delegation/SLA policy, or sandbox/live evidence is present. `NOT_CONFIGURED` / `ACTION_REQUIRED` presentation is the appropriate browser behaviour.
- **Secure-link action policy:** the current screen correctly offers authenticated in-app action only. An approved SSO/OTP/step-up/token-exchange design is required before implementing a secure-link handoff; do not place a token in route state, local storage, logs, or React state.
- **F05 execution:** readiness may show the server’s versioned handoff status, but F04 must not create procurement packages or invoices.

## Verification evidence

Executed from `/Users/arpan1.mukherjee/code/personal/vms-workflow` on 2026-07-26:

| Command | Result |
| --- | --- |
| `npm run typecheck` | Passed |
| `npm run test` | Passed: 12 files, 47 tests |
| `npm run build` | Passed; Vite reported the existing large-chunk advisory |
| `npm run lint` | Passed with 6 pre-existing fast-refresh warnings and no errors |

The passing test run is not F04 evidence: no certification/confirmation test file or Playwright specification exists. This is tracked as `F04-FE-003` rather than treated as a pass for `T-F04-UI-001` through `T-F04-UI-006`.
