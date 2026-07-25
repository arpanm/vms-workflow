# F00 Code Review Issues

| ID | Severity | Finding | Disposition |
|---|---|---|---|
| F00-CR-001 | Critical | Legacy migration grants `anon all` to all business tables. | Open; do not use for workforce production data. Replace with Spring authorization/PostgreSQL tests in F01. |
| F00-CR-002 | High | Browser route components directly insert/update business records. | Open; replace with secured Java APIs and auditable services. |
| F00-CR-003 | High | localStorage role selector can be mistaken for RBAC. | Open; F01 must remove it from production path. |
| F00-CR-004 | Medium | Shared `data-hooks.ts` couples all prototype domains to legacy storage. | Open; migrate by bounded context behind typed API clients. |
