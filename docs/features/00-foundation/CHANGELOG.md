# F00 Change Log

## 2026-07-25 — Foundation architecture correction

- Recorded baseline commit/tag and legacy migration checksum in the code index.
- Accepted Java/Spring Boot/PostgreSQL/JWT/Flyway/Testcontainers as the production target through ADR-010 and requirement 22.
- Reclassified the historical React/TanStack/Supabase code as a baseline-tag/rollback reference; its dependencies and migrations are removed from the working tree.
- Documented rollback prerequisites, validation commands, and blocked staging backup/rehearsal work.
- No application, database, or deployment change was made by this documentation artifact.
