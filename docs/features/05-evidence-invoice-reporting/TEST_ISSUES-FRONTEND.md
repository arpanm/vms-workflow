# F05 frontend test issue register

This register separates implemented frontend automation from gates that still
need browser, full-stack, security, provider or accessibility evidence. A
fixture-backed browser journey is useful contract/UI evidence, but is not proof
of Java authorization, PostgreSQL isolation, private storage or an external
provider.

## F05-FE-TEST-001 — Browser catalog authored but not executed in this follow-up

**Status:** Open validation gate.

`e2e/finance.spec.ts` and its dedicated Playwright project compile and pass
targeted lint, but the browser project was not run because this follow-up was
explicitly limited to frontend typecheck, lint and focused tests.

**Close with:**

```text
npx playwright test --project=f05-finance-chromium
```

Any locator, console, responsive-layout or runtime failure must be fixed before
claiming `T-F05-UI-001` through `T-F05-UI-006`.

## F05-FE-TEST-002 — Stateful route fixtures are not a live Java/RBAC test

**Status:** Open full-stack gate.

The finance fixture uses the exact current endpoint names, response fields,
cursor parameters and mutation headers, and it evolves returned state after
commands. It cannot prove JWT scope derivation, cross-tenant non-disclosure,
database row isolation, optimistic-lock races, audit/outbox atomicity or real
idempotency replay.

**Close with:** run the F05 Playwright journeys against the packaged Java
application and PostgreSQL synthetic tenant fixtures, including unauthorized,
wrong-tenant, inactive identity, stale ETag, reused-key/different-payload and
direct-ID cases. Keep the fixture project as a fast frontend regression layer;
do not replace the full-stack suite with it.

## F05-FE-TEST-003 — Real scan/storage/render/download behavior remains external

**Status:** External acceptance gate.

The quarantine browser case proves that the UI blocks download and keeps the
immutable replacement path available when the server reports
`QUARANTINED`. It does not execute malware inspection, object versioning,
renderer determinism, integrity recomputation, retention/legal hold, restored
object verification or short-lived download/share revocation.

**Close with:** `T-STOR-001` through `T-STOR-006`,
`E2E-F05-PROVIDER-001` and the approved synthetic provider environment. No
fixture, UI badge or generated local artifact can close this gate.

## F05-FE-TEST-004 — AP/ERP acceptance and payment segregation need live identities

**Status:** External/full-stack gate.

The browser catalog covers permission-hidden payment controls and append-only
status presentation. It does not prove AP/ERP callback authentication,
transition legality, duplicate callback handling, restricted-note masking per
vendor persona, reconciliation or separation of duties.

**Close with:** `T-PAY-001` through `T-PAY-003` and
`E2E-F05-PROVIDER-002` using distinct vendor, Procurement and AP identities.
Payment status must remain a recorded fact and must not be described as money
movement.

## F05-FE-TEST-005 — Automated accessibility depth is incomplete

**Status:** Open local/CI gate.

The screens use labelled native controls, fieldsets, status/alert roles,
non-color text status, captioned scrollable tables and named pagination
navigation. The current automation does not yet run axe, keyboard-only traversal
across every F05 form, focus/error recovery, screen-reader announcements,
contrast or tablet viewport assertions.

**Close with:** add automated axe and keyboard/tablet cases mapped to
`T-F05-UI-005`, followed by manual assistive-technology verification for the
uploader, readiness, package sharing/revoke, Procurement and export flows.

## F05-FE-TEST-006 — Export payload safety requires generated-file inspection

**Status:** Open backend/full-stack gate.

The frontend tests assert exact report/version/mode/filter requests and ensure
no grant token is returned to UI state. They do not inspect generated CSV/XLSX,
PDF or JSON bytes for formula escaping, output encoding, field masking,
integrity hash, scan result, expiry and dead-letter recovery.

**Close with:** execute `T-REP-004` through `T-REP-006` against real generated
artifacts and synthetic malicious values. The server renderer and private
download response are authoritative.

## Current evidence summary

- Focused F05 Vitest: 5 files, 22 tests, passed.
- Frontend TypeScript: passed.
- Targeted finance/E2E ESLint: passed with no warnings or errors.
- Playwright source/config TypeScript check: passed.
- F05 Playwright runtime: not run in this follow-up.
- Maven/backend: not run or modified in this follow-up.
- Provider/full-stack/accessibility/export-file acceptance: open as listed
  above.
