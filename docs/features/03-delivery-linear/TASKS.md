# F03 — Deliverable Planning and Linear Tasks

**Phase:** 3
**Requirements:** RQ-011–RQ-017; PRD 07, 09, 13–16, 19–20

- [ ] Add monthly plans, immutable plan versions, deliverables, acceptance criteria, dependencies and employee assignments.
- [ ] Enforce completeness, approval quorum, freeze/revision and baseline snapshot semantics.
- [ ] Send and retain commitment communication with Procurement CC validation.
- [ ] Implement server-only Linear OAuth/PKCE, connection/version records and allowlisted API adapter.
- [ ] Resolve issue identifiers and maintain links, normalized current projections and event history.
- [ ] Verify exact webhook body/signature/timestamp, deduplicate deliveries and process transactionally.
- [ ] Add reconciliation/manual refresh, retry/dead-letter queues and integration health.
- [ ] Capture plan-time and month-end immutable Linear snapshots.
- [ ] Ensure Linear `Done` never triggers delivery acceptance/certification.
- [ ] Build plan builder, review, issue-link and health UI states plus API/Swagger/user docs.
- [ ] Complete `T-PLAN`, `T-LIN`, Spring authorization/PostgreSQL scope, security and failure-injection automation and fix all accepted findings.

**Exit gate:** Lost/duplicate/replayed webhooks do not corrupt state; current Linear state cannot rewrite snapshots or certify delivery.
