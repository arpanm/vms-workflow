# F01 Code Generation Summary

F01 code generation used `gpt-5.6-sol` in two independent streams:

- [Java/PostgreSQL backend](CODEGEN-BACKEND.md)
- [Vite React frontend](CODEGEN-FRONTEND.md)

The backend provides a secured, tenant-scoped read vertical for identity and core masters. The frontend now uses typed HTTP APIs and contains no Lovable/Supabase runtime. [Independent review](CODE_REVIEW.md) and [fix disposition](FIXES.md) remain controlling for completion status.

This is not the full PRD 03 implementation: configurable roles/permissions, assignments, delegation, approval policies, mutations/audit events and a selected browser OIDC/BFF flow remain in the task backlog.
## Completion note — 2026-07-29

The generated implementation now consists of the V34 additive schema,
`CoreAdministrationController`, `CoreAdministrationService`, typed DTOs,
effective-permission/session changes, React administration routes and
deterministic browser fixtures. OpenAPI is generated from the executable
Spring controllers; frontend types mirror those schemas and no privileged
decision is derived from browser role state.
