# F04 frontend automation review issues

**Independent review:** 2026-07-26
**Disposition:** every item below is **Open**. These are automation/review findings; existing `F04-FE-001`–`012` remain in `CODE_ISSUES-FRONTEND.md` and are not duplicated here.

## F04-FE-013 — Confirmation screen overflows at the required tablet width

- **Severity:** P2
- **Kind:** Direct frontend responsive defect
- **Locations:** `e2e/certification.spec.ts:600-611`; `src/routes/confirmation.requests.$requestId.tsx:76-138,142-187`.
- **Evidence:** An independent Chromium run at 768 × 1024 returns `documentElement.scrollWidth > clientWidth`. The failure is reproducible after selecting correction; it is not a locator or fixture timeout.
- **Impact:** `T-F04-UI-006` tablet layout cannot be claimed. Exact-scope/diff content can become horizontally inaccessible to a keyboard or magnification user.
- **Recommendation:** Make identifier/table cells wrap or scroll within their owning region, use min-width-safe grid/table rules, and retain the 768 px document-overflow assertion plus a narrow-phone case.

## F04-FE-TEST-001 — Playwright fixture is not faithful to the current Java response contract

- **Severity:** P1
- **Kind:** Test-fixture contract defect
- **Locations:** `e2e/fixtures/certification-api.ts:153,229-243,275-297,638-644,706,799-805`; `backend/src/main/java/com/vms/workflow/api/CertificationDtos.java:260-303,339-359`.
- **Evidence:** `LinearSnapshotView.sourceVersionId`, `NotificationView.id/correlationId`, and `TimelineEventView.id/correlationId` are UUID fields in the Java DTO. The fixture supplies values such as `linear-plan-snapshot-v1`, `notice-read`, `timeline-draft`, and `corr-f04-draft`. It additionally supplies `MonthCertificationView.inboundReviews`, which the Java record does not define.
- **Impact:** Passing browser cases can consume shapes that the real controller cannot serialize. `BC-008` is especially misleading because its required review item is fixture-only.
- **Recommendation:** Generate browser fixture payloads from a shared JSON schema/OpenAPI or a typed backend fixture; use valid UUID strings and delete unsupported fields. Add a Java JSON serialization/contract test that validates the fixture examples.

## F04-FE-TEST-002 — Intercepted mutation endpoints do not enforce controller semantics

- **Severity:** P1
- **Kind:** Test-fixture contract defect
- **Locations:** `e2e/fixtures/certification-api.ts:461-472,543-818`; `backend/src/main/java/com/vms/workflow/api/CertificationController.java:73-230`.
- **Evidence:** Every fixture POST is accepted regardless of `If-Match`, `Idempotency-Key`, authentication, body validity, or endpoint state. Save, summary, and reopen are fulfilled as `200` (`e2e/fixtures/certification-api.ts:614,743,816`), while their controller methods return `201`.
- **Impact:** Browser tests can pass with requests a real controller would reject, and they cannot establish required request/header/error-status behavior.
- **Recommendation:** Fail fixture requests missing/malformed required headers and invalid bodies; mirror endpoint status/ETag behavior. Add Spring HTTP tests for all routes and treat that lane—not interception—as the API-contract proof.

## F04-FE-TEST-003 — Retry and accessible-validation failures use non-realistic test interactions

- **Severity:** P2
- **Kind:** Test design defect
- **Locations:** `src/features/certification/api.test.ts:49-53,164-175`; `e2e/certification.spec.ts:375-392`.
- **Evidence:** `F04-UNIT-API-004` invokes two successful mocked API calls directly, despite describing a lost response and a retained user intent. `BC-010` focuses and presses Enter on a disabled button, which cannot be activated by a user.
- **Impact:** The underlying `F04-FE-004` and `F04-FE-011` code defects are real, but these failures do not prove the described retry or keyboard behavior and can lead to an unsafe implementation targeted only at the tests.
- **Recommendation:** Test a mutation/UI retry after a deliberately unresolved/rejected transport response while passing the same intent key; test either a linked explanatory description while disabled or an enabled attempted submit that displays/focuses a real error summary.

## F04-FE-TEST-004 — Console and secret quality gate can be bypassed and is incomplete

- **Severity:** P2
- **Kind:** Test-quality/security-observability gap
- **Locations:** `e2e/fixtures/quality-gates.ts:5-33`; `e2e/certification.spec.ts:34-40,526-562,564-580`.
- **Evidence:** `allowExpectedHttpFailure` removes all `console` listeners, including the shared quality gate, for `BC-018`/`019`. Its replacement does not scan console/page-error text for secret sentinels, while `BC-020` scans only DOM, URL, and storage.
- **Impact:** A token/raw-MIME/provider-secret log could be accepted in the exact tests intended to prove error redaction, and browser-state secrecy has no console/network assertion.
- **Recommendation:** Keep the shared listener installed and make it allowlist only the expected status message. Capture console/page errors and recorded request/response metadata, asserting they contain no F04 restricted-content sentinels.

## F04-FE-TEST-005 — Required critical journeys remain absent from executable frontend evidence

- **Severity:** P1
- **Kind:** Coverage gap
- **Locations:** `docs/features/04-certification-confirmation/TEST_CASES.md:95-102`; `e2e/certification.spec.ts:43-640`; `e2e/fixtures/certification-api.ts:451-835`.
- **Evidence:** There is no executable retained-key lost-response flow, secure-link/CSRF/forwarded-token path, authorized inbound second-review action, scanner/evidence failure matrix, reviewer assignment/aging flow, or complete keyboard/screen-reader/mobile validation. The current controller exists but is never reached by the browser suite.
- **Impact:** Passing results do not satisfy `T-F04-UI-001`–`006` or security scenarios in `T-CONF-004`–`007` and `T-CONF-012`–`017`.
- **Recommendation:** Add route-aware browser cases where capability exists; add Spring Security/HTTP/Testcontainers cases for authoritative and token/inbound behavior; leave provider cases explicitly external rather than simulating them as passed.

---

## Post-fix independent review addendum — 2026-07-26

The 64 unit, 33 F04 Playwright, and 59 full Playwright results resolve the
fixture shape/status, retry, accessibility, secrecy-gate, responsive, and
critical-journey concerns recorded above within the deterministic browser lane.

**Disposition: resolved locally for browser-contract coverage; system lane
still required.** Add a browser-to-Java-to-PostgreSQL acceptance scenario when
the environment is available. It must include the backend exit cases in
`F04-TEST-010`; the present UI correctly performs inbound/manual *review* only
and cannot prove a reviewed record becomes a confirmation action until the
backend capability exists.
