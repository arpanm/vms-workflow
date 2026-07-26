# F03 test review

**Assessment:** the current integration test proves a narrow happy path, but it is not evidence for the F03 test catalog or release gate. No F03 Playwright scenario is present; existing browser tests are demo/workforce/legacy tests only.

## What is covered

`DeliveryLinearIT` covers plan create/submit/freeze/revision, one missing-link blocker, one valid raw-body callback/dedupe/process path, invalid HMAC/replay rejection, `Done` updating execution projection only, one cross-tenant read non-disclosure assertion, and OpenAPI secret-string checks ([DeliveryLinearIT.java](../../../backend/src/test/java/com/vms/workflow/integration/DeliveryLinearIT.java#L63)). This is valuable smoke coverage and is backed by Testcontainers/Flyway configuration ([DeliveryLinearIT.java](../../../backend/src/test/java/com/vms/workflow/integration/DeliveryLinearIT.java#L33)).

Frontend unit tests verify API URL encoding and selected presentation copy/validation, including that `Done` is not portrayed as certification ([presentation.test.ts](../../../src/features/delivery/presentation.test.ts#L128)). They mock the API boundary, so they do not prove backend contract compatibility or authorization.

## P0 test gaps

1. No test attempts mutation of every frozen/superseded content table or proves a webhook cannot alter frozen version content. The test only asserts baseline checksum updates fail ([DeliveryLinearIT.java](../../../backend/src/test/java/com/vms/workflow/integration/DeliveryLinearIT.java#L111)).
2. No tests cover `ALL`/`N_OF_M`, duplicate approvals, disabled/out-of-scope approvers, coordinator/vendor/product-owner conflict, stale authority, parallel approvals, double-freeze/outbox idempotency, checksum changes, rejection/rework, or no-deliverables exception.
3. No test proves connection-specific secret isolation. The suite configures one global `test-webhook-secret` ([DeliveryLinearIT.java](../../../backend/src/test/java/com/vms/workflow/integration/DeliveryLinearIT.java#L33)) while its fixture only records a dummy secret reference ([V1002__delivery_linear_test_fixtures.sql](../../../backend/src/test/resources/db/testdata/V1002__delivery_linear_test_fixtures.sql#L1)).
4. No tests cover cross-organization connection IDs, workspace/team/issue ownership, client-forged issue metadata, multi-link rationale, broken/inaccessible link retention, or out-of-order provider events.
5. No F03 E2E test exercises a browser request against the backend. `e2e/` contains no delivery spec, so the claimed end-to-end path is currently intercepted/mock truth at most, not production-contract truth.

## P1 test gaps

- Missing failure/operability coverage: worker crash, queue claim race, retry/backoff/dead-letter/replay, callback response latency, malformed/oversized/compressed bodies, rate limit, disabled connection, GraphQL HTTP-200 errors, pagination/checkpoints, reconciliation, snapshot fetch failures, and email outbox attempts.
- Missing Flyway-from-empty constraint tests for cross-engagement project, cross-connection, invalid enum/lifecycle direct SQL, data immutability, and migration compatibility/rollback rehearsal.
- Missing API contract tests for unauthenticated public/private routes, scope distinction/non-disclosure, validation status payloads, OpenAPI schema consistency, request-size/content-type behavior, and secret/non-secret fields.
- Missing accessibility and role-state tests for controls being absent/disabled, frozen non-editability, error summaries, keyboard navigation, snapshot/stale/imported/superseded labels, and no secret/token rendering.

## P2 test gaps

- Add deterministic boundary tests with an injected clock for timestamp parsing (seconds, milliseconds and ISO time), replay-window edges, provider `updatedAt` ordering, and date/allocation boundaries. The services create their own system clock, making this coverage unnecessarily flaky ([DeliveryPlanningService.java](../../../backend/src/main/java/com/vms/workflow/application/DeliveryPlanningService.java#L54), [LinearIntegrationService.java](../../../backend/src/main/java/com/vms/workflow/application/LinearIntegrationService.java#L45)).
- Generate/validate frontend fixtures from the Spring/OpenAPI response schema. Current hand-written types do not exercise nullable provider fields or all state/status enums, so an otherwise-green frontend unit suite can hide rendering failures.

## Fixture and isolation observations

`@Transactional` rolls test method data back but Flyway test fixtures are global schema state. The single connection, organization, subjects, and global callback secret invite accidental coupling between future methods. Provide fixture factories that create two organizations, two engagements/connections, separate secret-provider values, controlled clocks and unique delivery IDs. Never make external OAuth, live GraphQL, webhook registration, or real email part of local CI; keep those as separately labelled tenant-authorized acceptance gates.
