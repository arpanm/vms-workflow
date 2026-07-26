# F04 frontend code-generation handoff

## Implemented boundary

The provider-neutral React vertical consumes only authenticated Java APIs rooted at
`/api/v1/certification` (the shared browser client already supplies `/api/v1`).
Client mutations send an `If-Match` expected version and a fresh `Idempotency-Key`.
The browser submits intent and form content only; organization, role, recipient
eligibility, quorum, lock state, and final business transitions remain server
authoritative.

Typed boundaries:

- `GET /certification/months/{monthId}`
- `POST /certification/months/{monthId}/submissions`
- `POST /certification/submissions/{submissionId}/submit`
- `POST /certification/submissions/{submissionId}/clarifications`
- `POST /certification/submissions/{submissionId}/certifications`
- `POST /certification/months/{monthId}/summaries`
- `GET /certification/months/{monthId}/readiness`
- `POST /certification/months/{monthId}/confirmation-requests`
- `GET /certification/confirmation-requests/{requestId}`
- `POST /certification/confirmation-requests/{requestId}/actions`
- `POST /certification/months/{monthId}/reopen-requests`

Routes:

- `/certification` — scoped month entry
- `/certification/$monthId` — vendor draft, criteria, safe evidence references,
  declaration, submit lock, clarification response, snapshots, and timeline
- `/certification/$monthId/review` — assigned item review, frozen/vendor/decision
  comparison, criterion decisions, clarification, partial carry-forward, and
  explicit monthly summary
- `/confirmation` — scoped month entry
- `/confirmation/$monthId` — five-pillar readiness, blockers/owners/CTAs, exact
  recipient/quorum/version preview, request creation, lineage, restricted safe
  inbound metadata, notification state, and reopen impact
- `/confirmation/requests/$requestId` — exact scope/diff, eligibility, in-app
  action, audit/action history, request lineage, and transport/provider state

All material screens include server-version freshness and read-only indicators,
loading/empty/error states, safe 404-style permission denial, typed validation,
locked and version-conflict messaging, correlation references, non-color status
labels, labelled keyboard-operable controls, and responsive layouts. The screens
state explicitly that Linear state, completion percentage, transport receipts,
silence, reminders, and timeouts do not infer certification or confirmation.

## Privacy and provider boundary

Frontend contracts intentionally contain no token hash, plaintext token, raw
MIME, provider message body/header, signed object URL, storage credential, email
provider ID, callback secret, or provider credential. Evidence is represented by
safe server-managed reference metadata and scan status. Inbound/manual review is
limited to a server-redacted summary and authentication/review labels when the
permission is present. There are no Lovable or Supabase imports or dependencies.

## Explicit pending integrations

The shared API contract has no endpoint for artifact upload/scan initiation,
artifact viewing, inbound/manual reviewer mutation, notification replay, or
background-job controls. The UI therefore displays existing safe evidence and
inbound/notification state but does not invent those mutations.

The confirmation response route implements authenticated in-app action. A
secure-link token handoff needs the approved SSO/OTP/step-up design and a
server-defined exchange flow; no token is read into or rendered from React state.
Live email and controlled-mailbox acceptance remain externally blocked and are
shown only through `NOT_CONFIGURED` / `ACTION_REQUIRED` server state.

F05 procurement package and invoice behavior is not implemented. Readiness shows
only the versioned F04 handoff eligibility returned by the server.

## Validation evidence

Executed after route-tree generation:

- `npm run typecheck` — passed
- `npm run test` — 12 files, 47 tests passed
- `npm run build` — passed
- `npm run lint` — passed with the repository's existing fast-refresh warnings
  and no errors
