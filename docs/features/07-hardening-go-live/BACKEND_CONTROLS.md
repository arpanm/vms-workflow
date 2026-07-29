# F07 backend local-control evidence

This note covers repository-local backend controls only. It does not certify a
production OIDC tenant, database platform, TLS proxy, provider, deployment,
scanner, object store, retention schedule, legal approval or operations
acceptance.

## PostgreSQL roles and startup boundary

`V21__f07_database_least_privilege.sql` creates six passwordless capability
roles:

| Capability role | Local contract |
|---|---|
| `vms_migration_owner` | Flyway/DDL capability marker; never an application-pool role |
| `vms_app_runtime` | API business read/write; no schema creation or Flyway access |
| `vms_reporting` | Read-only business tables; no token, blob, audit or security-event access |
| `vms_job_worker` | Exact certification/finance scheduled call-graph grants; no identity/RBAC, migration source, provider secret or private-blob read |
| `vms_migration_processor` | Exact leased scan/validation recovery grants; no commit/rollback, identity/RBAC/provider secret or direct source-blob read |
| `vms_backup` | Read-only backup coverage, including restricted persisted records |

The roles are `NOLOGIN`, `NOSUPERUSER`, `NOCREATEDB`, `NOCREATEROLE`,
`NOREPLICATION` and `NOBYPASSRLS`. Platform provisioning must create distinct
login principals, grant only the applicable capability role, and provide
credentials out of band.

The `prod` profile uses `VMS_DATABASE_MIGRATION_USERNAME` and
`VMS_DATABASE_MIGRATION_PASSWORD` for Flyway while the normal datasource
continues to use `VMS_DATABASE_USERNAME` and `VMS_DATABASE_PASSWORD`.
`VMS_DATABASE_RUNTIME_ROLE` must match `current_user`; startup also verifies
the exact expected capability membership, rejects every other runtime
capability, and proves the principal cannot create in `public`, mutate
`flyway_schema_history` or inherit the migration owner.

The `worker-certification`, `worker-finance` and `worker-migration` profiles
set `spring.main.web-application-type=none`, disable Flyway, and enable exactly
one scheduler. Their distinct LOGIN secrets set both
`VMS_DATABASE_USERNAME` and `VMS_DATABASE_WORKER_LOGIN`. API nodes use none of
these profiles and all worker switches default false.

Migration recovery calls a package-private service path with the fixed
`SYSTEM:F06_RECOVERY` actor; no controller can route to it and it performs no
user authorization lookup. PostgreSQL denies the processor direct access to
`migration_source_blobs`. The only byte path is
`f07_migration_leased_source(job_id, lease_owner)`, a fixed-search-path,
`SECURITY DEFINER` function that returns content only while the caller's
unguessable lease is current.

The migration revokes business object access from `PUBLIC`, removes
update/delete/truncate from append-only audit/security streams, restricts
tokens and blobs from reporting, removes default `PUBLIC` grants, and pins all
non-extension application functions to `search_path = pg_catalog, public`.
Future forward migrations must grant each new object explicitly.

`F07DatabaseRoleIT` is the Testcontainers proof for the grant matrix, leased
source gate, negative identity/RBAC/secret/DDL checks and function catalog.
Worker-specific integration suites execute the finance, certification and
migration worker paths. This is intentionally not production database
acceptance.

## HTTP/browser boundary

- The API is bearer-only and has no cookie/BFF session surface. CSRF is
  disabled for this boundary; adding cookie authentication requires a separate
  CSRF and secure-cookie design before release.
- `VMS_CORS_ALLOWED_ORIGINS` is a comma-separated exact-origin allowlist.
  Wildcards, wildcard-like values and `null` fail startup. Browser credentials
  are disabled.
- Every response receives CSP/frame denial, no-sniff, no-referrer, restrictive
  permissions policy and no-store headers. HSTS is emitted for direct TLS or
  when a single `X-Forwarded-Proto: https` value arrives from an exact address
  in `VMS_TRUSTED_PROXY_ADDRESSES`. Untrusted and ambiguous forwarded values
  fail closed. The production profile disables container-level forwarded
  header reinterpretation so this allowlist is the single trust boundary.
  Production networking must still prevent direct access around that proxy.
- `VMS_MAX_JSON_REQUEST_BYTES` rejects oversized non-multipart mutation bodies
  before parsing (1 MiB by default, fail-fast maximum 4 MiB). Governed file
  uploads use the independent 25/26 MiB Spring multipart limits and are never
  copied by the JSON filter. The production proxy/container must also enforce
  concurrent-connection and minimum-body-rate limits against slow uploads.
- Attendance mutations, migration mutations and Linear webhooks now join the
  existing certification and finance durable rate-limit controls. A limit-store
  failure fails closed; `429` includes `Retry-After` without actor or store
  details.
- Unexpected API exceptions log only correlation ID and exception type.
  Responses contain a stable generic detail and correlation ID; stack traces,
  SQL text and exception payloads are not returned or written by the global
  handler.
- Linear issue references use an exact `https://linear.app` URI policy that
  rejects HTTP, subdomain tricks, user-info, non-default ports, fragments and
  local/metadata addresses. There is no general outbound HTTP client in the
  current backend. Any future client must also disable redirects or revalidate
  every redirect and impose connect/read/payload bounds.

## Health and management

Anonymous access is limited to:

- `/actuator/health`
- `/actuator/health/liveness`
- `/actuator/health/readiness`

Health components and details are always redacted. Readiness contains
`readinessState` plus the mandatory database contributor. Liveness contains
only `livenessState`. Optional certification provider capabilities report
aggregate `DEGRADED` state but are excluded from readiness so an optional
provider outage is not confused with mandatory database failure. Actuator
`info` is disabled and all other actuator routes are denied. Swagger/OpenAPI
remains bearer-authenticated.

## Audit, retention and legal hold scope

The F07 database grants add a second enforcement layer to the existing
append-only audit/security triggers. Existing finance artifact legal-hold
changes remain mediated by `FinanceArtifactGovernanceService` and immutable
transition records; direct hold mutation is rejected by PostgreSQL. Existing
migration sources carry `retention_until` and are immutable.

No repository change invents a statutory retention duration or claims a
general deletion job is complete. Cross-domain retention execution, legal-hold
authority/dual-control policy, production archive behavior and approved
durations remain `ACTION_REQUIRED` until separately implemented and approved.

## Local verification

Non-Docker focused checks:

```text
cd backend
mvn -q -DskipTests test-compile
mvn -q -Dtest=OutboundUriPolicyTest,SecurityHeadersFilterTest,RequestSizeLimitFilterTest,OptionalProviderHealthIndicatorTest,DatabaseRoleGuardTest,SecurityConfigTest,CoreRateLimitFilterTest test
```

The PostgreSQL 18 Compose health check uses a three-minute initialization
`start_period`, followed by ten bounded five-second readiness retries, matching
the Testcontainers/bootstrap startup budget without declaring a healthy slow
initialization failed. Validate the rendered definition with
`docker compose -f backend/compose.yaml config`.

Docker/Testcontainers cases to run in an isolated release-gate job:

```text
cd backend
mvn -q -Dit.test=F07DatabaseRoleIT,F07HttpHardeningIT failsafe:integration-test failsafe:verify
```

These controls provide local evidence for F07-T011–T013, T016, T018–T020,
T022–T024, T042 and T048. They are partial supporting evidence for F07-T043–
T044; the remaining retention/legal-policy scope is explicitly not claimed.
