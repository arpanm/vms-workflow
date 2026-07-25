# ArrowFoundry × Reliance Intelligence — Workforce, Delivery and Invoice Evidence Governance

**Document pack version:** 1.0
**Prepared on:** 25 July 2026
**Intended implementation environment:** Existing `arpanm/vms-workflow` application
**Primary implementation users:** Cursor, Claude Code, product owners, engineering, QA, procurement governance and administrators

## Start here

Read [`22_JAVA_POSTGRES_ARCHITECTURE_OVERRIDE.md`](22_JAVA_POSTGRES_ARCHITECTURE_OVERRIDE.md) first. It is the controlling production-stack addendum: Java 25, Spring Boot 4.1.0, Maven, springdoc 3.0.3, PostgreSQL, JWT/OIDC, Flyway, and Testcontainers replace the Supabase/Lovable/TanStack-specific implementation instructions in PRD 13 and PRD 17. Then read and execute [`00_INDEX_IMPLEMENTATION_TODO.md`](00_INDEX_IMPLEMENTATION_TODO.md) for sequencing, scope, dependencies, acceptance gates and file references.

This pack replaces the seven high-level draft PRDs as the implementation specification for the new ArrowFoundry resource-governance use case. The drafts remain useful source material, but are not sufficiently deterministic for implementation on their own.

## Product outcome

Extend the existing Monthly Delivery Governance prototype into a multi-organization, multi-engagement governance system that can prove, for each billing month:

1. who was deployed and their approved project/deliverable allocation;
2. attendance, leave and regularization outcomes for every billable resource-day;
3. which deliverables were committed before the month and their linked Linear work;
4. what was delivered and accepted by authorized Reliance Intelligence product owners;
5. that a consolidated month-end confirmation was sent and authentically confirmed; and
6. which immutable, versioned evidence package supports the submitted invoice.

Salary, individual compensation, markup calculations and rate-card arithmetic are explicitly excluded.

## Recommended reading order

1. `22_JAVA_POSTGRES_ARCHITECTURE_OVERRIDE.md`
2. `00_INDEX_IMPLEMENTATION_TODO.md`
3. `01_PRODUCT_CHANGE_BRIEF_SCOPE_AND_PRINCIPLES.md`
4. `02_DOMAIN_MODEL_ORGANIZATIONS_ENGAGEMENTS_PROJECTS_MONTHS.md`
5. `03_IDENTITY_RBAC_APPROVAL_MATRIX_AND_ADMINISTRATION.md`
6. `04_EMPLOYEE_MASTER_ALLOCATIONS_CALENDARS_AND_LEAVE_BALANCES.md`
7. `05_ATTENDANCE_CHECKIN_CHECKOUT_LEAVE_AND_REGULARIZATION.md`
8. `06_GREYTHR_INTEGRATION_SOURCE_OF_TRUTH_AND_RECONCILIATION.md`
9. `07_MONTHLY_DELIVERABLE_PLANNING_AND_LINEAR_INTEGRATION.md`
10. `08_DELIVERY_EVIDENCE_CERTIFICATION_AND_MONTH_END_CLOSURE.md`
11. `09_EMAIL_CONFIRMATION_INGESTION_NOTIFICATIONS_AND_ESCALATIONS.md`
12. `10_INVOICE_EVIDENCE_PROCUREMENT_AND_PAYMENT_READINESS.md`
13. `11_HISTORICAL_MIGRATION_BACKFILL_AND_RETRO_APPROVALS.md`
14. `12_DASHBOARDS_REPORTS_EXPORTS_AND_CONTROL_TOWER.md`
15. `13_DATA_MODEL_API_EVENTS_BACKGROUND_JOBS_AND_STORAGE.md` (business/API intent; stack-specific instructions superseded)
16. `14_SECURITY_PRIVACY_AUDIT_RETENTION_AND_COMPLIANCE.md`
17. `15_UI_UX_INFORMATION_ARCHITECTURE_AND_PERSONA_FLOWS.md`
18. `16_ACCEPTANCE_TEST_CATALOG_NFR_ROLLOUT_AND_OPERATIONS.md`
19. `17_EXISTING_CODE_IMPACT_FILE_LEVEL_TODO_AND_MIGRATION_ORDER.md` (domain/migration intent; stack-specific instructions superseded)
20. `18_IMPORT_TEMPLATES_FIELD_DICTIONARY_AND_SAMPLE_FILES.md`
21. `19_RESEARCH_FINDINGS_AND_INTEGRATION_DECISIONS.md`
22. `20_ASSUMPTION_REGISTER_CONFIG_DEFAULTS_AND_DECISION_LOG.md`
23. `21_REQUIREMENT_TRACEABILITY_AND_GAP_CLOSURE.md`

After reading the specifications, use `CURSOR_START_HERE.md` as the execution handoff. `IMPLEMENTATION_BACKLOG.csv` provides an importable phase/task list.

## Artifact conventions

- `MUST`, `SHALL`: mandatory for release.
- `SHOULD`: expected unless a documented architecture decision says otherwise.
- `MAY`: optional or phase-later.
- Dates are stored as ISO dates; timestamps are stored in UTC and displayed in the user's configured timezone.
- Business-month examples use `YYYY-MM`, e.g. `2026-06`.
- Every mutable business record uses effective dating and versioning where historical evidence can be affected.
- Every procurement-relevant transition generates an immutable audit event.
