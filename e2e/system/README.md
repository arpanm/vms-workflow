# F05 and F06 real-system Playwright runners

This runner verifies F05 against the packaged application boundaries rather
than browser request interception:

```text
Playwright -> Vite proxy -> Spring Security/API -> Flyway schema -> PostgreSQL
                         \-> local JWKS (RS256 scoped synthetic identities)
```

Run it from the repository root:

```bash
npm run e2e:finance:system
npm run e2e:migration:system
```

Prerequisites are Docker, Java 25, Maven, Node.js dependencies and the
Playwright Chromium browser. The runner:

1. creates an ephemeral digest-pinned Chainguard PostgreSQL 18 container on a
   random local port;
2. starts a process-local JWKS endpoint and signs one-hour JWTs for the
   synthetic vendor, Procurement, finance, governance and outsider identities;
3. starts Spring Boot with production migrations plus the existing synthetic
   test fixtures and `e2e/system/db/V2000__finance_system_e2e_seed.sql`;
4. starts Vite on a random local port with the dedicated non-production system
   token bridge enabled;
5. runs only the selected `f05-finance-system-chromium` or
   `f06-migration-system-chromium` Playwright project; and
6. terminates both servers and removes the PostgreSQL container on success,
   failure, `SIGINT` or `SIGTERM`.

The suite does not install Playwright route interception. It covers real
invoice create/upload/scan, exact package/readiness/submission, Procurement
query/review and exception guard, finance payment history, restricted export
denials, expiring and revoked download authority, cross-scope denial, and a
browser-rendered finance workspace backed by the real API.

The F06 runner additionally covers server-derived migration scope/template
catalog, multipart upload and scan, validation/reconciliation, forged-role
denial and independent SoD approval, commit with domain visibility, audit and
unconsumed compensation, safe error export/rejected-only reprocess, and
current-time retro requests.

## Authentication safety

`VITE_E2E_SYSTEM_AUTH=true` is recognized only by a Vite development build.
`validatePublicEnvironment` rejects it in production, and the in-memory token
provider reads only the system-runner session-storage key. No backend bypass,
unsigned token mode, static production key or persisted local-storage token is
added. Spring continues to validate RS256 signature, issuer, audience and
expiry through its normal resource-server configuration.

## Evidence

HTML output is written to
`node_modules/.cache/playwright-system-report`; traces, screenshots and videos
are written to `node_modules/.cache/playwright-system-results`. These generated
artifacts are local evidence and are not source-controlled.
