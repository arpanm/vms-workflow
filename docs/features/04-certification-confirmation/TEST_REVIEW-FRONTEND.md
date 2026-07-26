# F04 frontend automation — independent review

**Reviewed:** 2026-07-26
**Scope:** F04 Vitest, Playwright, fixture, current React routes/contracts, current Java DTO/controller contract, `TEST_CASES.md`, the F04 automation record, and the E2E catalog. No product or test code was changed.

## Verdict

The suite is useful browser-contract evidence and its stable IDs are unique (`F04-UNIT-*` 11/11 and `E2E-F04-BC-001`–`024` 24/24). There are no `skip`, `only`, or `fixme` cases, and each Playwright test receives its own intercepted mutable fixture, so one case does not mask another.

It is **not an F04 frontend completion gate**. The 11 reported failures are reproducible, but two assertions do not realistically exercise the event they describe: the idempotency test does not model a lost response or a retained user intent, and the accessibility test attempts to activate a disabled button. The current fixture also drifts from the Java DTO/controller contract and does not enforce the server's required request semantics. Passing browser cases therefore prove only the intercepted React rendering/request shape, not the Java/Security/PostgreSQL path.

## Independent execution

- `npx vitest run src/features/certification/api.test.ts src/features/certification/presentation.test.ts src/features/certification/formatting.test.ts` — **10 passed, 1 failed**. `F04-UNIT-API-004` generated two UUIDs.
- Targeted Playwright reruns reproduced `BC-002`, `BC-004`, `BC-006`, `BC-008`, `BC-010`, and `BC-022`. The failure modes match the current route/UI code. `BC-022` reproduces document-level horizontal overflow at 768 px.
- I did not count the historical full-suite result as a fresh run. Its recorded `14 passed, 10 failed` is internally consistent with the targeted results and the 24 stable E2E IDs.

## Failure-classification audit

| Reported failure | Review disposition | Independent basis |
|---|---|---|
| `F04-UNIT-API-004` → `F04-FE-004` | **Validate underlying product defect; reject as sufficient retry evidence.** | `mutationHeaders` creates a UUID for every API invocation ([api.ts](../../../src/features/certification/api.ts#L19-L24)). That violates retained-idempotency intent. The test makes two successful direct client calls; it never loses a response, invokes the mutation hook, or retains an intent key ([api.test.ts](../../../src/features/certification/api.test.ts#L164-L175)). |
| `E2E-F04-BC-002` → `F04-FE-005` | **Validated.** | Submit calls `submit.mutate(month.submission.version)` without saving dirty controlled state ([certification.$monthId.tsx](../../../src/routes/certification.$monthId.tsx#L439-L453)). The test correctly observes only the submit POST. |
| `E2E-F04-BC-004` → `F04-FE-001` | **Validated.** | The generated route is nested, but its parent renders `VendorSubmissionPage`, not an `Outlet` ([certification.$monthId.tsx](../../../src/routes/certification.$monthId.tsx#L42-L50); [route tree](../../../src/routeTree.gen.ts#L470-L475)). The deep route cannot render its child. |
| `E2E-F04-BC-006` → `F04-FE-009` | **Validated.** | The default takes `slice(0, 16)` and drops `+05:30`, then later relies on browser-local `new Date()` ([confirmation.$monthId.tsx](../../../src/routes/confirmation.$monthId.tsx#L187-L190), [confirmation.$monthId.tsx](../../../src/routes/confirmation.$monthId.tsx#L249-L254)). In the configured New York browser, `18:30 +05:30` should be `09:00`; the UI shows `18:30`. |
| `E2E-F04-BC-008` → `F04-FE-007` | **Validated as a contract/capability gap.** | The UI is display-only ([confirmation.$monthId.tsx](../../../src/routes/confirmation.$monthId.tsx#L323-L370)); the current Java DTO has neither inbound reviews in `MonthCertificationView` nor a review-decision mutation ([CertificationDtos.java](../../../backend/src/main/java/com/vms/workflow/api/CertificationDtos.java#L339-L359)). The fixture's extra `inboundReviews` property cannot make this a real route capability. |
| `E2E-F04-BC-010` → `F04-FE-011` | **Validate underlying accessibility defect; reject the failed interaction as realistic.** | Required content silently disables the action with no validation/error state ([confirmation.requests.$requestId.tsx](../../../src/routes/confirmation.requests.$requestId.tsx#L257-L300)). However, the test focuses and presses Enter on that disabled button ([certification.spec.ts](../../../e2e/certification.spec.ts#L380-L392)); a disabled control cannot be an attempted submit. Test either preemptive `aria-describedby`/explanatory text, or an enabled submit that creates a linked error summary. |
| `E2E-F04-BC-018` → `F04-FE-010` | **Validated.** | Error invalidation refetches query data ([hooks.ts](../../../src/features/certification/hooks.ts#L123-L127)), but the form copies initial query data into `useState` and never reconciles it ([certification.$monthId.tsx](../../../src/routes/certification.$monthId.tsx#L78-L113)). |
| `E2E-F04-BC-019` → `F04-FE-012` | **Validated.** | The query boundary appends arbitrary `ApiError.message` into the alert ([query-boundary.tsx](../../../src/features/certification/query-boundary.tsx#L103-L120)); `ApiError.message` is populated from server `message`, `detail`, `title`, or raw text ([api-client.ts](../../../src/lib/api-client.ts#L101-L122)). |
| `E2E-F04-BC-022` → no prior issue ID | **Validated; record as `F04-FE-013` (P2).** | The 768 px browser run reports document horizontal overflow. The exact-scope card contains unbreakable identifiers in a fixed sidebar/tablet layout; this is a real responsive failure, not a selector timeout. |
| `E2E-F04-BC-023` → `F04-FE-006` | **Validated as a frontend/contract gap.** | Vendor criteria have only a response textarea ([certification.$monthId.tsx](../../../src/routes/certification.$monthId.tsx#L335-L365)); the only evidence input is a deliverable-level free-ID field ([certification.$monthId.tsx](../../../src/routes/certification.$monthId.tsx#L369-L405)). The server DTO lacks an authorized selectable-reference list. |
| `E2E-F04-BC-024` → `F04-FE-008` | **Validated as a contract gap.** | The route can render only `sourceVersionIds` ([confirmation.requests.$requestId.tsx](../../../src/routes/confirmation.requests.$requestId.tsx#L95-L102)); current `ConfirmationRequestView` exposes only that string list and one scope checksum ([CertificationDtos.java](../../../backend/src/main/java/com/vms/workflow/api/CertificationDtos.java#L423-L447)), not named source records/hashes. |

## Automation-quality findings

| Area | Result | Evidence |
|---|---|---|
| Stable IDs and isolation | Pass | IDs are unique and catalogued; no focused/skipped cases. The fixture state is local to `mockCertificationApi` per page. |
| Selectors | Mostly sound | Role/label selectors exercise accessible names rather than brittle CSS. `BC-004` uses soft assertions and returns after the route failure, so it records the blocker but cannot prove its deeper reviewer assertions until reachability is fixed. |
| Fixture fidelity | Fail | The fixture sends non-UUID linear snapshot IDs, timeline IDs/correlation IDs, and other values where the Java records require UUIDs; it also adds `inboundReviews` that the Java month DTO does not return. Save, summary, and reopen fixtures return `200` where the controller returns `201`. See `F04-FE-TEST-001`. |
| Request/body/header assertions | Partial | The Vitest client test checks all eight mutations' `If-Match`/idempotency headers. The browser fixture accepts every mutation regardless of required headers and does not assert bodies/headers for all browser paths; it never proves authentication. See `F04-FE-TEST-002`. |
| Console/secret gates | Partial | The shared console/page-error gate is a good default, but `BC-018` and `BC-019` remove its console listener. The substitute only allows an expected network error; no test asserts secret sentinels are absent from console/page-error text or intercepted request/response bodies. See `F04-FE-TEST-004`. |
| Retry realism | Fail | `F04-UNIT-API-004` calls two resolved mocks, not a committed-but-lost response followed by an explicit retry of the same UI intent. See `F04-FE-TEST-003`. |
| Accessibility/responsive claim | Partial | Named controls and one radio-keyboard movement are covered, but required errors, focus/error recovery, submission/reviewer/reopen keyboard paths, and mobile/screen-reader semantics are not. The sole tablet overflow case fails. |
| Regression/non-masking | Pass with boundary | The suite does not suppress failures and recorded pre-F04 browser cases remain separate. Intercepted browser tests cannot certify the Java regression lane. |

## Missing critical journeys

The current 24 E2E cases do not supply executable evidence for these required F04 journeys:

- A real retained-key retry after the server commits but the browser loses the response, including a second click/explicit retry and proof of one business result.
- Secure-link/forwarded-token, CSRF, wrong-user, and token replay handling through the Java/Security route; browser interception cannot prove these `T-CONF-004`–`007` controls.
- Reviewer inbox assignment/aging and evidence-view authorization; the current DTO cannot support them.
- Authorized inbound/manual second-review approve/reject/quarantine with a reason and safe redaction; the current fixture invents a read-only payload.
- Submission validation boundaries (scan pending/quarantined, MIME/filename/evidence failures, invalid percentages, missing criterion responses) and all mandatory-state request rejections.
- Keyboard/screen-reader error recovery for vendor, review, confirmation, and reopen, plus phone and tablet layouts after error expansion.
- Java controller status/header/authentication/ETag and DTO serialization contracts. A controller now exists ([CertificationController.java](../../../backend/src/main/java/com/vms/workflow/api/CertificationController.java#L63-L230)); the earlier “no controller” frontend issue is stale, but the intercepted suite still does not exercise it.

## Gate conclusion

Keep every validated product/contract failure **Open**. Do not mark `T-F04-UI-001`–`006`, the F04 security/browser claims, or the frontend completion gate passed from this suite. First correct the product/contract defects, make the fixture conform to current Java DTO/controller behavior, replace the two unrealistic assertions, then add a Spring HTTP contract lane alongside the browser lane.
