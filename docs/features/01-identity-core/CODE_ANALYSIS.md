# F01 Frontend Code Analysis

> Analysis snapshot: backend observations below were recorded before the
> hardening passes. Their current resolution is cross-linked in
> [FIXES.md](FIXES.md); this file is retained as independent review evidence.

## Architecture result

The frontend now follows `route → React Query hook → typed fetch client → /api/v1` rather than direct browser Supabase access. `index.html`/`src/main.tsx` provide a Vite SPA entry point; TanStack Router still provides client routing and React Query remains the cache boundary. Supabase clients/types/migrations, Lovable configuration, Cloudflare runtime configuration and TanStack Start server files are deleted from the working tree.

## Remaining analysis findings

- This is an adapter migration, not a completed canonical frontend: all existing screens still consume flat historical compatibility collections, while the new Java domain has canonical organization/engagement/month resources.
- The API client is a good centralized boundary but only supplies TypeScript assertions after JSON parsing. It does not validate successful compatibility response shapes.
- Authentication is currently an architectural seam, not an implementation: the SPA supports either cookie inclusion or a memory bearer provider, but no code establishes either credential and the server only understands bearer JWTs.
- Legacy feature gating is enforced at navigation and route load time, but not as a server entitlement; that is appropriate only because the Java server separately enforces authorization. F01-T11's current-org/current-engagement scope remains missing.

No application code was modified by this review. The recommended repair order is F01-CR-001, F01-CR-002, F01-CR-003, then canonical scoping/types.

## Backend code analysis — 26 July 2026

### Current request flow

`Bearer authentication → controller extracts JWT subject → CatalogQueryService/LegacyQueryService → TenantAuthorizationService membership lookup → JPA/JdbcTemplate → PostgreSQL`

The layering is clear and `open-in-view` is disabled. Collection queries authorize before loading data, and the legacy JDBC identifier is protected by a two-stage allowlist. The service is read-only except for Flyway startup.

### Trust-boundary analysis

- The JWT trust boundary is incomplete because issuer is not validated, while tests inject an already-authenticated mock token.
- The authorization decision is organization membership only. Role codes are descriptive data and never become permission checks; project/object assignments do not exist.
- The database is not a second least-privilege boundary: migrations and runtime share one datasource identity.
- Direct-ID services load globally and authorize afterward, preserving row confidentiality but exposing object existence through 403/404 differences.
- Active user/membership/date filters are correctly expressed in repository queries, but inactive organization state is ignored and empty memberships sometimes produce successful empty responses.
- OpenAPI reflects executable controllers at runtime, but no build gate proves its security scheme, errors, or client compatibility.

### Phase 1 completeness

Implemented: application bootstrap, PostgreSQL/Flyway startup, basic organization/user/membership/engagement/project/month tables, synthetic fixtures, tenant-filtered read APIs, immutable legacy reads, JWT resource-server skeleton, authenticated Swagger/OpenAPI, and four Testcontainers cases.

Missing: canonical permission model and scoped assignments; identity-provider/session metadata; invitations/revocation; configuration versioning; teams/contacts; approval policies/actions/delegation; guarded state transitions/history; PostgreSQL roles; canonical mutations; Bean Validation request DTOs; optimistic versions, idempotency and audit; typed domain errors; issuer/key-rotation proof; full authorization matrix; OpenAPI generation/client verification; and staging evidence.

No application or backend code was modified by this review. Recommended backend repair order: issuer/real-JWT proof, fail-closed principal/scope resolution, RBAC/object scopes, database least privilege/schema constraints, then the remaining Phase 1 domain and contract.
