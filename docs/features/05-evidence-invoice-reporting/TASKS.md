# F05 — Evidence, Invoice and Reporting Tasks

**Phase:** 5
**Requirements:** RQ-024–RQ-026, RQ-032; PRD 10, 12–16

- [ ] Add immutable evidence artifacts/package versions/items with canonical serialization and SHA-256 manifests.
- [ ] Generate deterministic manifest, PDF and CSV content from exact roster, attendance, plan, Linear, certification and confirmation versions.
- [ ] Add invoice/version metadata, secure upload, readiness runs/results and uniqueness/correction lineage.
- [ ] Add procurement review, explicit exception, queries and payment history.
- [ ] Enforce private storage paths, scan/quarantine, MIME/size rules and short signed URLs.
- [ ] Build persona dashboards, exception control tower and filter/source/version-labelled exports.
- [ ] Prevent salary, CTC, markup, employee rate or derived invoice calculation fields/exports.
- [ ] Add package history/diff and supersession behavior.
- [ ] Add APIs/OpenAPI, metric dictionary and persona UI documentation.
- [ ] Automate `T-PKG`, `T-INV`, `T-PROC`, `T-PAY`, `T-REP`, storage/Spring authorization/PostgreSQL/security tests and fix findings.

**Exit gate:** Same closed-month version regenerates the same canonical manifest/checksums; unauthorized/package-cross-month access fails.
