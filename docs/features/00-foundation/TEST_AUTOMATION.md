# F00 Test Automation

## Present

- Vitest unit tests cover legacy environment validation and feature-flag parsing.
- The harness checker verifies planning artifacts and model separation.

## Missing / required for target platform

No Java test suite or Testcontainers PostgreSQL test currently exists. F01 must add JUnit/Spring Boot tests for JWT authentication, authorized scope, denied cross-tenant scope, Flyway migration from empty PostgreSQL, and API contract generation. F00 staging smoke/backup tests remain manual and blocked by unprovided environments.
