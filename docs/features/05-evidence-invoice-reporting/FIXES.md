# F05 — Fix record

## Fixes applied after independent review

The following local remediations are present in the current F05 source and
were confirmed by the combined regression:

- F04 resolver validates contract schema, source IDs/checksums, readiness,
  exact scope and compatible handoff version; consumption/invalidation is
  journaled.
- Artifacts persist private bytes, hashes and scan provenance. Package/export
  download recalculates byte hashes and local production configuration fails
  closed without an enabled scanner.
- Database guards cover immutable package/invoice/readiness/artifact lineage,
  scope-bound child links and valid state transitions.
- Package generation requires exact current F04 and invoice inputs, records
  explicit unavailable-upstream-binary disclosure, supports manifest/PDF/CSV/
  XLSX/JSON outputs, shares, expiry and revocation.
- Effective policy governs upload constraints, readiness/exception behavior,
  retention/classification and policy labels.
- Payment status requires authorized Finance scope and exact approved lineage;
  it remains an append-only recorded AP/ERP fact rather than funds movement.
- Export worker has bounded leases/retries/dead-letter/replay, private output
  scan/hash validation, formula-safe tabular rendering and authority snapshot.
- Finance organization discovery, query response visibility, opaque cursors,
  rate limits and correlation-safe server error handling were tightened.
- The exception state guard and package PDF evidence output were corrected after
  the last static review and are covered by the passing regression.

## Fixes found by real-system execution

The bounded browser-to-Java-to-PostgreSQL run found and closed three defects
that fixture-only tests did not expose:

1. Finance month reads selected nonexistent
   `engagement_months.optimistic_version`. All list/keyset/workspace queries now
   use the migrated F04 `certification_version`; `FinancePaginationIT` asserts
   the response version against the persisted schema value.
2. The application supported exact-input approval after a resolved
   Procurement clarification, but the database transition guard rejected
   `CHANGES_REQUESTED → APPROVED_FOR_PROCESSING`. Forward migration V15 adds
   only that transition while retaining immutable represented fields and all
   other state checks. The integration and system flows now create, respond to,
   close and approve the query.
3. An expired share remained blocked by the old partial unique index, while a
   second share also collided with a package-version domain-event key. Forward
   migration V16 replaces the index with a non-overlapping validity-window
   exclusion constraint, and share events now use their own `PACKAGE_SHARE`
   aggregate (create v1, revoke v2). Expiry, later re-share, explicit revocation
   and cross-scope denial are all exercised.

The final `npm run e2e:finance:system` result is **3/3 passed** against a fresh
PostgreSQL 18 database with Flyway V1–V16 plus synthetic test migrations.

## Still not closed

Historical issue registers preserve findings and remediation traceability.
No local G0–G3 failure remains. Performance/scale evidence and external G4
provider/deployment approvals remain release-hardening work.

## 2026-07-30 retention and upload-policy fixes

- Added V45 finance-content record classes to the existing organization-scoped
  F07 schedule/dry-run/explicit-execution lifecycle. V45 seeds no duration.
  Direct blob/state bypass fails; dry-run reports held/referenced candidates;
  concurrent approved executions produce one disposal.
- Preserved artifact metadata/hash/lineage after disposal and emitted an
  immutable retention transition, audit, domain event and outbox fact.
- Both finance record classes remain fail-closed until an authorized schedule
  version is configured. External non-transactional storage cannot opt into
  local disposal without a durable provider deletion state machine.
- Replaced the UI's stale hard-coded retention value with the effective
  policy returned on invoice reads.
- Converted overlapping package-share database conflicts into a safe typed
  domain conflict and added a committed two-caller race.
- Corrected architecture/status text: package/readiness are synchronous
  governed mutations; notifications are outbox contracts; export is the local
  background worker and content retention requires governed dry-run/execute.
