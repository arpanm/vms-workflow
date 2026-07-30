# PRC-002 — Approve or reject invoices

**Persona:** Client procurement user
**Implementation:** IMPLEMENTED — F05 versioned Procurement review
**Testing:** VERIFIED — finance concurrency/system suites

## Description and UI flow

Open a pending invoice, review evidence and record approved, rejected or
changes-requested decision with a reason.

## Acceptance criteria

- Procurement authority and exact invoice version are required.
- Decisions are append-only/idempotent and audited.
- Concurrent stale reviewer loses with typed conflict.

## Test cases

- Approve/reject paths update queue.
- Unauthorized and stale decisions fail.
