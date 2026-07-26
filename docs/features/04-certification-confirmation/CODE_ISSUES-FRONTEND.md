# F04 frontend review issues

**Status convention:** every entry is **Open**. “Browser-contract limitation” means the frontend must not invent a client-side workaround; the API owner needs to supply the named server-authoritative capability. Severity is assessed against F04-TASK-023/-024 and `TEST_CASES.md`.

## Blockers

### F04-FE-001 — Nested product-owner review route has no outlet

- **Severity:** Blocker
- **Kind:** Direct frontend defect
- **Disposition:** Open
- **Locations:** `src/routes/certification.$monthId.review.tsx:44-47`; `src/routes/certification.$monthId.tsx:1-75`; `src/routeTree.gen.ts:470-475,522-531`.
- **Impact:** The generated tree makes `/certification/$monthId/review` a child of `/certification/$monthId`, but `VendorSubmissionPage` does not render TanStack Router’s `Outlet`. Navigation from the “Product-owner review” link can render only the parent workspace rather than the review component. Product-owner certification is therefore unavailable, blocking `F04-TASK-023` and `T-F04-UI-002`.
- **Recommendation:** Make the month route a layout that renders an `Outlet`, or make the vendor and review pages sibling routes (for example, move the vendor page to an index child). Add a route-level Playwright assertion that the review heading and decision controls appear at the deep link.

### F04-FE-002 — All F04 browser calls target API routes absent from the available backend

- **Severity:** Blocker
- **Kind:** Browser-contract limitation
- **Disposition:** Open
- **Locations:** `src/features/certification/api.ts:28-88`; `backend/src/main/java/com/vms/workflow/api/CertificationDtos.java:19-447`.
- **Impact:** The frontend calls nine `/api/v1/certification/...` resources, but the available Java implementation contains DTOs/authorization helpers only; repository inspection found no certification controller or request mapping. The UI therefore receives safe 404/error states rather than a locally working F04 vertical. DTO declarations alone do not make the contract executable.
- **Recommendation:** Implement and test secured Spring controllers/application services for every route in `api.ts`, including typed validation/conflict responses and ETags/idempotency. Contract-test the generated JSON shape against `contracts.ts` before treating F04 UI as integrated.

### F04-FE-003 — No automated F04 frontend evidence exists

- **Severity:** Blocker
- **Kind:** Acceptance evidence gap
- **Disposition:** Open
- **Locations:** `docs/features/04-certification-confirmation/TEST_CASES.md:95-102`; `src/features/certification/api.ts:1-89`; `e2e/delivery.spec.ts:1-120` (existing F03 pattern; no F04 counterpart exists).
- **Impact:** `npm run test` passes 47 tests, but none load F04 code and no F04 Playwright fixture/specification exists. The route defect and mutation issues below consequently passed unchecked. `T-F04-UI-001` through `T-F04-UI-006` and the required F01–F03 regression claim cannot be evidenced from this suite.
- **Recommendation:** Add API/client/unit tests for request headers, error classification, state resets, and date handling; add mocked-server Playwright journeys for vendor, reviewer, governance, in-app action, safe denial, stale/conflict, keyboard flow, and no-secret rendering. Include them in the normal test command or an explicitly required F04 gate.

## High-severity issues

### F04-FE-004 — Idempotent retries use a different key than the original intent

- **Severity:** P1
- **Kind:** Direct frontend defect
- **Disposition:** Open
- **Locations:** `src/features/certification/api.ts:19-24,31-88`; `src/features/certification/hooks.ts:44-61,94-127`.
- **Impact:** `mutationHeaders` creates a new UUID every time a mutation function is invoked. If the server commits a submission, confirmation request, action, or reopen but the response is lost, a user retry is a new idempotency key, not the authorized replay required by F04-TASK-004/-015 and `T-CONF-007`. The result can be a conflict or duplicate business attempt instead of the prior result.
- **Recommendation:** Generate an idempotency key once per user intent and retain it through transport failures/retry UI until a definitive response is received. Pass that key as mutation input, scope it to the operation/object/version, and only generate a replacement after a completed/cancelled intent.

### F04-FE-005 — “Submit exact version” ignores unsaved visible edits

- **Severity:** P1
- **Kind:** Direct frontend defect
- **Disposition:** Open
- **Locations:** `src/routes/certification.$monthId.tsx:129-138,435-453`.
- **Impact:** Draft fields are local until “Save draft,” while “Submit exact version” calls `submit` with `month.submission.version`. A vendor can edit criteria/outcomes/declaration and immediately submit; the server submits the older persisted draft rather than what the button labels as the exact visible version. This risks an unintended declaration and fails the vendor journey in `T-F04-UI-001`.
- **Recommendation:** Track dirty state. Disable submit with an explicit “Save the draft before submitting” message, or save then submit the returned version as one intentional, recoverable workflow. Test the unsaved-edit path.

### F04-FE-006 — Criterion-level evidence cannot be added from the vendor workflow

- **Severity:** P1
- **Kind:** Direct frontend defect / browser-contract dependency
- **Disposition:** Open
- **Locations:** `src/features/certification/contracts.ts:251-255`; `src/routes/certification.$monthId.tsx:99-110,338-364,369-405`.
- **Impact:** The request supports `criterionResponses[].evidenceReferenceIds`, but the UI only preserves IDs returned by the server; it exposes a comma-separated input for deliverable-level IDs only. A vendor cannot attach/select safe existing evidence for a criterion, so criterion evidence expectations can be impossible to satisfy and the reviewer cannot complete the evidence-driven path required by `T-F04-UI-001`/`T-F04-UI-002`.
- **Recommendation:** Once the server supplies an authorized safe-reference list and artifact workflow, add an accessible per-criterion selector that sends only selected IDs. Do not accept arbitrary artifact URLs, content, signed URLs, or provider data in React state.

### F04-FE-007 — Inbound/manual-review screen is display-only and unreachable from the DTO draft

- **Severity:** P1
- **Kind:** Browser-contract limitation
- **Disposition:** Open
- **Locations:** `src/routes/confirmation.$monthId.tsx:323-367`; `src/features/certification/contracts.ts:177-187`; `backend/src/main/java/com/vms/workflow/api/CertificationDtos.java:339-359`.
- **Impact:** The TypeScript field is optional, the Java month view defines no inbound-review payload, and neither contract defines a restricted reviewer decision/reason mutation. Authorized reviewers therefore cannot receive a record, apply the required second review, or decide/reject/quarantine it. `T-F04-UI-005`, F04-TASK-018, and F04-TASK-024 cannot pass.
- **Recommendation:** Add a redacted inbound/manual-review DTO plus authorized review-action endpoints. Return only the classified metadata and audit references appropriate to the caller; keep raw MIME, headers, attachments, tokens, and provider credentials server-side.

### F04-FE-008 — Assigned/aging inbox and human-readable exact scope are absent from the browser contract

- **Severity:** P1
- **Kind:** Browser-contract limitation
- **Disposition:** Open
- **Locations:** `src/routes/certification.$monthId.review.tsx:111-117`; `src/routes/confirmation.requests.$requestId.tsx:89-101`; `backend/src/main/java/com/vms/workflow/api/CertificationDtos.java:218-230,339-359,423-447`.
- **Impact:** The review page labels every month deliverable as an assigned inbox item but receives neither assignment nor aging data, and the confirmation page can show only opaque source-version IDs plus a scope checksum—not named attendance/plan/baseline/certification source versions and hashes. This cannot satisfy the assigned/aging and exact-scope portions of `T-F04-UI-002` and `T-F04-UI-004` without guessing from client state.
- **Recommendation:** Extend the server response with scoped assignment, age/due/SLA status, and a safe named immutable scope manifest (source kind, ID/version, checksum/freshness). Filter the inbox server-side; do not use browser role claims or cached dates to infer assignment/readiness.

### F04-FE-009 — Default confirmation due date loses its server offset

- **Severity:** P1
- **Kind:** Direct frontend defect
- **Disposition:** Open
- **Locations:** `src/routes/confirmation.$monthId.tsx:187-190,226-254`; `backend/src/main/java/com/vms/workflow/api/CertificationDtos.java:319-328`.
- **Impact:** `defaultDueAt.slice(0, 16)` strips the `OffsetDateTime` offset before assigning a `datetime-local` value. Converting that text back with `new Date(...).toISOString()` interprets it in the operator’s browser zone. A policy/default due time can therefore be shifted by hours before the confirmation request is created.
- **Recommendation:** Convert the server instant explicitly into the displayed timezone, show that timezone next to the input, and convert the submitted local value back with an explicit offset. Cover non-UTC and DST browser zones in tests.

## Medium-severity issues

### F04-FE-010 — Query updates do not reconcile controlled form state

- **Severity:** P2
- **Kind:** Direct frontend defect
- **Disposition:** Open
- **Locations:** `src/routes/certification.$monthId.tsx:78-113`; `src/routes/certification.$monthId.review.tsx:127-175`; `src/routes/confirmation.$monthId.tsx:373-382`; `src/routes/confirmation.requests.$requestId.tsx:190-209`.
- **Impact:** Each form initializes `useState` from the first query result. Mutation success replaces the React Query cache and error handling refetches, but the controls retain their old values. A later submit can send stale fields with the new server version, overwriting a concurrent correction rather than making the user review it; action forms also retain values after state changes.
- **Recommendation:** Key form components by immutable object/version, or deliberately reset/rebase state on a returned version after warning about unsaved local edits. On 409/412, discard/reconcile the draft and direct focus to a current-version summary before enabling another mutation.

### F04-FE-011 — Required-field failures are silently disabled rather than explained

- **Severity:** P2
- **Kind:** Direct frontend/accessibility defect
- **Disposition:** Open
- **Locations:** `src/routes/certification.$monthId.review.tsx:170-175,404-429`; `src/routes/confirmation.requests.$requestId.tsx:257-300`; `src/routes/confirmation.$monthId.tsx:382-463`.
- **Impact:** Reviewer rationale/comment/partial-scope, confirmation correction reason, and reopen fields affect button `disabled` state, but no field-level error, error summary, `aria-describedby`, or explanation tells a keyboard/screen-reader user why the action cannot proceed. This falls short of F04-TASK-023/-024 and `T-F04-UI-006`.
- **Recommendation:** Validate on attempted submit, render a focusable error summary linked to invalid controls, set `aria-invalid`/`aria-describedby`, and keep the action explanation available without relying on color or a silently disabled button.

### F04-FE-012 — F04 renders arbitrary server error detail into the page

- **Severity:** P2
- **Kind:** Direct frontend defense-in-depth defect
- **Disposition:** Open
- **Locations:** `src/features/certification/query-boundary.tsx:103-120`; `src/lib/api-client.ts:101-122`; `backend/src/main/java/com/vms/workflow/api/ApiExceptionHandler.java:21-55`.
- **Impact:** Although the screen starts with a safe denial message, it appends `ApiError.message`, which is created from server `message`/`detail`/raw text. A future F04 endpoint that returns restricted evidence, token, MIME, recipient, or SQL-adjacent detail would display it to the browser. This weakens the intended redaction/no-scope-disclosure boundary in `T-F04-SEC-002`/-003.
- **Recommendation:** For F04, display only curated error-code messages and correlation ID; show field errors from an allowlisted structured payload. Ensure the backend maps authorization/not-found and validation failures to redacted typed codes rather than exception text.

## External acceptance gates (not code defects)

The following remain external prerequisites and should stay visible as `NOT_CONFIGURED` / `ACTION_REQUIRED`, not be “fixed” by browser code:

- **F04-FE-GATE-001:** G4 provider/send/inbound acceptance — approved sender, controlled mailbox, callback signature material, retention decision, recipient/quorum/delegation/SLA policy, and sandbox/live proof are absent.
- **F04-FE-GATE-002:** approved SSO/OTP/step-up and secure-link token-exchange design is absent. The in-app route is appropriate; no token should enter URL state, browser storage, React state, logs, or rendered UI.
- **F04-FE-GATE-003:** F05 package/invoice execution is outside F04. Display only the server-returned versioned readiness handoff status.

---

## Post-fix independent review addendum — 2026-07-26

The history above remains intact. The post-fix React implementation resolves
the direct UI defects in `F04-FE-001`–`012`, including route nesting,
idempotency intent retention, safe criterion selectors, redacted review
controls, scope presentation, timezone conversion, rebase/error treatment,
and responsive/accessibility behavior. The 64 unit and 33 F04 Playwright
results are useful local evidence for those claims.

**Disposition: resolved locally, with no separate frontend P1 found.** The
remaining end-to-end limitation is not a client workaround issue: Playwright
uses an intercepted fixture and must be followed by browser-to-Java-to-
PostgreSQL acceptance. In particular, the reviewed inbound/manual UI must not
be claimed to create confirmation quorum until backend `F04-BE-008` supplies
the reviewed-evidence-to-action transition. The controlled mailbox, secure
token exchange/SSO, evidence storage, and F05 consumer remain external gates.
