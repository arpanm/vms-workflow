# F07 — Security Analysis

## Implemented controls

| Threat | Control |
|---|---|
| Forged scope/role/flag | JWT identity plus active PostgreSQL authority; server-derived scope; feature flags only enable code paths and cannot grant permission. |
| Cross-origin browser abuse | Exact-origin CORS, no wildcard/null origin and no credentialed browser CORS. Current API uses bearer tokens, so CSRF is disabled; any future cookie/BFF mutation must add CSRF and secure cookie controls. |
| XSS/error leakage | React safe rendering, centralized safe error presentation, RFC 7807 correlation IDs, no stack/SQL/token payload in clients. |
| Clickjacking/content injection | CSP with `frame-ancestors`, frame denial, nosniff, referrer/permissions policy, no-store and HTTPS-only HSTS. |
| Oversized/chunked body | Multipart limits plus bounded non-multipart mutation-body pre-read and hard maximum. |
| SSRF/credential forwarding | HTTPS-only exact-host URI policy with user-info, fragment, port and subdomain-confusion rejection. |
| Webhook/command abuse | Durable known/unknown callback buckets, trusted-proxy-aware client address, actor/scope rate limits, retry metadata and idempotency controls. |
| Database privilege escalation | Separate NOLOGIN capabilities, no runtime migration membership, fixed function paths, revoked PUBLIC/default privileges and restricted reporting view. |
| Legal-hold bypass | Governed transition service plus database trigger guarding the legacy mutation path. |
| Commercial-data leakage | Classification inventory and database JSON-key guard for salary/rate/markup and common derived variants. |
| Evidence substitution/replay | Commit-bound provenance, structured result records, HMAC-authenticated backup manifest, freshness/replay and canonical-path checks. |

## Independent findings

The initial reviews found a broken production migration bootstrap, lock hazards,
runtime migration-role assumption, retention concurrency/recovery gaps,
chunked-size bypass, commercial-key bypass, webhook bucket abuse, null proof
hashes, legal-hold bypass and multiple release/backup substitution paths. The
findings and current code dispositions are preserved in
[CODE_REVIEW.md](CODE_REVIEW.md) and [FIXES.md](FIXES.md).

## Residual risk

- Green local regression does not substitute for commit-bound artifact scans,
  external security evidence or final post-fix review.
- The current optional scanner/storage/email/provider implementations do not
  establish approved production services.
- Real secrets, key rotation, MFA/step-up, on-call delivery, penetration
  testing, privacy/legal approval and DR/capacity evidence require external
  owners.
- The full supply-chain scan must run on the exact release artifacts; its
  source configuration alone is not a pass.

## Current verification boundary

The recorded independent security review closes code through `c2d8dfb`.
Current V39/V40 and release-control changes are not silently inherited by that
review and require exact-candidate re-review.

The local security-relevant evidence is green for 73 unit + 45 integration
tests, the 73 + 2 capacity lane, the 7/7 F07 system lane, the 4/4 finance
system lane, the 6/6 migration system lane and the 274/274 browser matrix.
Static/typecheck/lint/diff gates also pass. The browser failure history
(268/274, then 7/7 exact slice, then 274/274) remains recorded.

Definitive Maven R3 passes 290/290 (73 unit + 217 integration), with zero
failures/errors/skips. The preserved R2 215/217 result was a shared
test-database isolation issue and the dedicated worker database passes. Real
provider certification, OIDC/IdP lifecycle, approved secret/scanner/storage
services, legal/privacy
approval, penetration testing, production telemetry/on-call, production-like
soak/load/DR and human release approval are not established locally.

Production decision: **NO-GO / ACTION_REQUIRED** until those records are
present, current and independently approved.
