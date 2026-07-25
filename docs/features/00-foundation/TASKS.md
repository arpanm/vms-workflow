# F00 — Foundation Tasks

**Phase:** 0
**Requirements:** PRD 00 §5 Phase 0; PRD 14 §§4–6,13; PRD 17 §§2,8; PRD 22
**Exit gate:** Legacy routes remain a documented rollback reference; Java/PostgreSQL target, rollback, environment and validation requirements are documented.

## Ordered backlog

- [x] F00-T01 Record baseline commit `5e463c7`, local tag `baseline/pre-workforce-20260725`, historical legacy migration checksum, target stack and known access risks.
- [x] F00-T02 Record strict typed configuration requirements: production Java services reject missing JWT/database/provider configuration; legacy demo mode remains non-production only.
- [x] F00-T03 Record legacy compatibility controls (`VITE_DEMO_MODE` plus legacy, workforce, greytHR, Linear and reply-ingestion flags) with safe defaults; do not use them as Java authorization controls.
- [x] F00-T04 Define frontend validation plus target Java `mvn -B -f backend/pom.xml verify`, Flyway and Testcontainers PostgreSQL validation commands.
- [x] F00-T05 Add a machine-checkable SDLC artifact/model harness.
- [ ] F00-T06 Capture a staging source schema/data/object-storage metadata backup. Requires a user-selected staging source and approved encrypted destination; never infer or export production.
- [ ] F00-T07 Record staging and production PostgreSQL/deployment references without credentials.
- [ ] F00-T08 Demonstrate legacy rollback smoke and Java target health/auth smoke in staging-like environments.

## Files

- `requirements/22_JAVA_POSTGRES_ARCHITECTURE_OVERRIDE.md`
- `docs/architecture/ADR-010-JAVA-POSTGRES.md`
- `docs/architecture/CODE_INDEX.md`
- `docs/operations/ROLLBACK.md`
- `scripts/sdlc-harness.mjs`
- `sdlc/harness.config.json`

## Rollback

Disable new Java workflow routes/jobs, deploy the previously approved application version, and restore PostgreSQL only from a documented pre-migration backup when approved. Keep legacy fixed-cost behavior only as an explicitly controlled compatibility path. Additive objects can remain dormant if rollback would risk deleting newly recorded evidence.

## Definition of done

- All planning artifacts exist and are linked.
- Invalid production Java configuration fails before request handling.
- The codegen and review model IDs differ.
- Available legacy validation passes; Java validation is run once the backend exists.
- External staging backup tasks are either evidenced or explicitly blocked with owner/input.
