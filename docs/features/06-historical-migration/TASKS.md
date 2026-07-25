# F06 — Historical Migration Tasks

**Phase:** 6
**Requirements:** RQ-027–RQ-029; PRD 11, 13–14, 16, 18

- [ ] Add import jobs/rows, staged validation, dry-run, idempotency, row provenance and error reports.
- [ ] Validate every supplied template version/checksum and protect CSV formula content.
- [ ] Import in dependency order: masters, allocations/calendars, attendance/leave, plans/links, certifications, confirmations, invoices.
- [ ] Reconcile row counts, identifiers, business totals and canonical checksums before publish.
- [ ] Distinguish imported facts, reconstructed records and newly obtained approvals.
- [ ] Add retro commitment/certification/confirmation workflows without falsifying original timestamps.
- [ ] Add historical close/reopen/version lineage and exception-owner signoff.
- [ ] Build migration center, batch/row correction and recovery UI.
- [ ] Add API/Swagger/template/operator docs and rollback/quarantine runbook.
- [ ] Automate `T-MIG` and `E2E-08`, review security/data-quality findings and fix them.

**Exit gate:** June onward packages are reproducible and provenance distinguishes represented time from recorded/approved time.
