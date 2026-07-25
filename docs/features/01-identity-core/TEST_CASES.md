# F01 — Identity and Core Test Cases

| ID | Source | Scenario | Expected |
|---|---|---|---|
| T-IAM-001 | PRD 16 | Valid invited user signs in | Session resolves to active profile/membership |
| T-IAM-002 | PRD 03 | Unauthenticated browser/direct Java REST query | No business row returned or mutated |
| T-IAM-003 | RQ-001 | Org A member reads Org A master data | Allowed within granted scope |
| T-IAM-004 | PRD 03 | Org A member reads Org B data by forged UUID | Denied through Spring authorization and PostgreSQL-backed integration test |
| T-IAM-005 | PRD 03 | Project A owner reads Project B-only object | Denied |
| T-IAM-006 | PRD 03 | Disabled member reuses a valid session | Protected request denied |
| T-IAM-007 | RQ-033 | Change local demo role in production | Authorization unchanged; switcher absent |
| T-IAM-008 | PRD 03 | Vendor submits a client certification mutation | Denied even with forged payload |
| T-IAM-009 | PRD 03 | Service account records business confirmation | Denied |
| T-IAM-010 | RQ-034 | Legacy flag on/off | Old routes operate when on and are intentionally unavailable when off |
| T-CORE-001 | PRD 02 | Client and vendor organization are identical | Constraint rejects engagement |
| T-CORE-002 | PRD 02 | Month date is not first of month | Constraint rejects row |
| T-CORE-003 | PRD 02 | Duplicate engagement/month pair | Unique constraint rejects row |
| T-CORE-004 | PRD 02 | Invalid month transition | Typed rejection and audit/transition evidence |
| T-CORE-005 | PRD 02 | Config effective August queried for July | July resolves previous version |
| T-AUTHZ-001 | PRD 14 | Inspect every exposed Java endpoint/service policy | JWT authentication plus organization/object permission is required; no anonymous business endpoint |
| T-AUTHZ-002 | PRD 14 | Direct API insert changes organization ID outside scope | Spring service rejects it and transaction persists no row |
| T-AUTHZ-003 | PRD 14 | Reporting/query across tenants | Controller/service scope filter and PostgreSQL integration test preserve tenant isolation |

Every authorization case must run for allowed and denied actors against a Flyway-migrated Testcontainers PostgreSQL database and, before release, an approved staging PostgreSQL environment.
