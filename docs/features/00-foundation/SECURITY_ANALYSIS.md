# F00 Security Analysis

## Findings

- **Critical:** the legacy schema enables RLS but then grants anonymous read/write access to all five business tables.
- **High:** a client-side role selector is not identity or authorization.
- **High:** direct browser mutations have no authoritative tenant/object authorization boundary.
- **High:** a service-role client exists; it must remain server-only and is replaced by Java server configuration/service design.
- **Medium:** the legacy design has no demonstrated audit, idempotency, immutable evidence, provider-secret isolation, or backup/restore evidence.

## Required remediation gate

F01 must prove JWT/OIDC validation, method/service authorization, organization/engagement scope checks, secrets isolation, Flyway migration validation, and cross-tenant denial using Spring tests with Testcontainers PostgreSQL. No sensitive source data may be loaded before that evidence exists.
