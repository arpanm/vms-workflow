# F05 — Test review

## Review result

**Conditional NO-GO pending execution.** The automation design covers the
highest-risk F05 boundaries, but the final combined run has not yet produced
fresh evidence for all newly added backend tests and browser cases.

## Strengths

- Testcontainers/Flyway cases exercise finance schema guards and exact private
  byte/hash behavior rather than only mocked services.
- The frontend suite treats the opaque cursor, idempotency key and `If-Match`
  protocol as part of the contract.
- Browser fixtures intercept the public API boundary and mutate server-shaped
  state; they do not reach into React component state.
- The test catalog explicitly separates local checks from provider/deployment
  acceptance, preventing a local scanner or intercepted browser from becoming
  false production evidence.

## Required review closure

1. Execute the full backend F05 lane after shared-target coordination clears.
2. Run the actual F05 Playwright project, then the repository end-to-end suite.
3. Add independent-transaction concurrency, worker lease-loss/restart,
   corruption/expired-download and natural exception-state coverage.
4. Add automated accessibility (axe/keyboard/tablet) and performance/DR
   evidence or retain explicit approved exceptions.

Detailed backend and frontend findings are retained in
[TEST_ISSUES-BACKEND.md](TEST_ISSUES-BACKEND.md) and
[TEST_ISSUES-FRONTEND.md](TEST_ISSUES-FRONTEND.md).

## 2026-07-30 focused re-review

Focused execution closed the dashboard DTO/full-scope aggregate defect and
added expiry/checksum download denial evidence. It did not run a full
repository regression. The remaining required closure is natural
blocked-readiness exception coverage, independent commit/concurrency and
lease-loss/restart fault injection, the updated system-browser journey,
performance/DR, and the explicitly external provider/Procurement gates.
