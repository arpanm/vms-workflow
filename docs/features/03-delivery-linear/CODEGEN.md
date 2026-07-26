# F03 Code Generation

**Date:** 2026-07-26
**Scope:** provider-neutral local demonstrator; no live Linear, OAuth, webhook
registration or commitment-mail provider is claimed.

## Delivered local slice

- Flyway V7–V10 establish delivery-plan/version evidence, recorded Linear
  connection/link/current/snapshot/webhook records, scoped permissions and
  terminal-evidence integrity rules.
- Java services/controllers provide draft creation, submit, approval/freeze,
  revision-by-clone, recorded issue linking, signed webhook receipt, authorized
  processing, health and OpenAPI routes.
- React routes provide planning, plan detail/review, issue evidence and
  integration-health views. Browser code submits opaque IDs only; it has no
  provider credentials or direct Linear call.
- V10 resolves the reviewed local P0 integrity findings: terminal approval
  evidence, freeze/baseline/outbox atomicity, and frozen-version ownership and
  lineage protections. See [FIXES.md](FIXES.md).

## Evidence

| Lane | Recorded result | Boundary |
|---|---|---|
| Frontend unit/contract | 47 tests passing | Local presentation/contract evidence |
| Spring/Testcontainers PostgreSQL | 49 tests passing | Local Java, Flyway and HTTP integration evidence |
| Playwright Chromium | 26 intercepted cases passing (8 F03/cross-feature) | Browser-contract only; APIs are deterministic fixtures |

The implemented state path is narrower than the planned catalog. Local P1 work
remains open, including an autonomous queue/retry worker, complete
reconciliation, complete exception/revision workflow, exhaustive quorum and
perimeter controls. External production inputs remain separately blocked.

## Source records

- [Backend generation record](CODEGEN-BACKEND.md)
- [Frontend generation record](CODEGEN-FRONTEND.md)
- [API contract](API_DOCUMENTATION.md)
- [UI guide](UI_DOCUMENTATION.md)
- [Test evidence](TEST_AUTOMATION.md)
