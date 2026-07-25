# F01 — Identity, Tenant Boundary and Core Master Tasks

**Phase:** 1
**Requirements:** RQ-001, RQ-033, RQ-034; PRD 02, 03, 13 §§3.1–3.2,5, 14 §5, 17, 22
**Exit gate:** An authenticated user cannot access another organization/engagement through UI, secured Java API, service layer, or PostgreSQL-backed integration test.

## Java backend, database and authorization

- [x] F01-T01 Add organizations, user profiles, memberships, roles, permissions, role permissions and scoped role assignments.
- [ ] F01-T02 Add canonical engagement fields/config versions, projects, teams, contacts/contact groups and engagement role assignments without dropping legacy columns.
- [ ] F01-T03 Add approval policy versions/stages, requests/actions and effective-dated delegation foundations.
- [ ] F01-T04 Add engagement months, guarded state transitions and append-only transition history.
- [x] F01-T05 Add Spring Security JWT/OIDC principal extraction, method/service authorization and a typed organization/engagement scope resolver.
- [ ] F01-T06 Add PostgreSQL roles/least-privilege access as required; prohibit anonymous legacy data access in the replacement path and do not rely on client/UI controls for authorization.
- [ ] F01-T07 Add constraints for organization separation, unique scope keys, first-of-month dates and state/version integrity.
- [x] F01-T08 Add generic seed data for ArrowFoundry, Reliance Intelligence, Central Procurement, NAM/Agentic ShopOS and the June 2026 engagement; no employee or payroll data.

## Application

- [ ] F01-T09 Add OIDC/JWT login/logout/session integration and protected Spring API endpoints; document issuer, audience, claims and key-rotation configuration.
- [x] F01-T10 Make demo role switching available only in non-production demo mode with a visible banner.
- [ ] F01-T11 Add current organization/engagement/month scope and permission-aware navigation.
- [x] F01-T12 Replace direct privileged route mutations with typed Spring controllers/application-domain services and OpenAPI contracts.
- [x] F01-T13 Preserve existing route URLs behind the legacy flag and remove unsafe auto/deemed-approval messaging/actions.
- [ ] F01-T14 Add typed domain errors for unauthorized, conflict, invalid transition and stale version.

## Verification and operations

- [x] F01-T15 Add JUnit/Spring integration tests with Testcontainers PostgreSQL for own scope, wrong engagement, wrong organization, disabled membership and forged HTTP requests.
- [ ] F01-T16 Generate/validate OpenAPI from executable controllers; generate frontend client types only from that contract if needed.
- [x] F01-T17 Validate Flyway from an empty PostgreSQL database, `mvn -B -f backend/pom.xml verify`, Testcontainers integration tests, frontend typecheck/lint/test/build where applicable.
- [x] F01-T18 Document authorization matrix, JWT flow, OpenAPI contracts, UI flow, Flyway/rollback and Phase 1 exit evidence.

Unchecked items are intentionally incomplete. In particular, provider-backed
browser login/BFF, contacts and approval/delegation domains, controlled month
transitions, database owner/runtime-role separation, current-scope UI, and
generated-client/error-contract gates still prevent the full Phase 1 exit gate.

## Definition of done

- No anonymous business-data API or service path remains.
- Browser role state has no effect on authorization.
- Database, JWT and provider secrets are never exposed client-side.
- Tenant tests execute against Java plus Testcontainers PostgreSQL, not only mocked functions.
- Legacy routes remain reachable when enabled.
- Phase 2 remains disabled until the tenant-isolation exit gate is evidenced.
