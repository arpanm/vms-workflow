# F03 UI Documentation

## Local screens

- **Delivery plans:** monthly plan list and a nested draft builder.
- **Plan detail:** completeness feedback, checksum/recipient evidence,
  approvals, frozen read-only content and revision entry point.
- **Linear evidence:** recorded issue links, current versus snapshot evidence,
  source/freshness/status labels and stale/inaccessible presentation.
- **Integration health:** provider-registration/readiness, sanitized error and
  queue/dead-letter summary states.

## Safety and user meaning

The browser never receives Linear credentials or calls Linear directly. Link
creation sends only a draft deliverable version, connection ID, issue UUID and
optional rationale; the server resolves recorded metadata. Controls are gated
by workflow state, while the server remains the authorization authority.

Frozen content is presented read-only. `Done`/`COMPLETED` means execution
evidence only; it does not imply acceptance, certification, confirmation or
invoice eligibility. Sent/read commitment communication also never implies
approval. Provider-unavailable, stale, broken and inaccessible states must be
read as evidence/readiness conditions, not success.

## Known limits

The local UI is a narrow demonstrator. It lacks a server-backed connection/issue
picker, caller-specific capability fields, complete revision diff/effective
baseline comparison and operator replay/refresh controls. Playwright provides
intercepted browser-contract evidence only; it is not a real BFF, Java,
PostgreSQL or live-provider journey.
