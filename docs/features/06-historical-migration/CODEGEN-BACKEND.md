# F06 Backend Implementation

The Java/PostgreSQL backend implements the governed migration path at
`/api/v1/migrations`. Flyway V17 adds scoped immutable source retention,
staging rows/findings, dependencies, decisions, approvals, checkpoints,
canonical provenance, reconciliation/sign-off, compensation, retro requests,
audit and exactly-once outbox records.

The server-owned template registry defines all 14 physical CSV templates,
exact v1 headers, dependency order, natural keys, source/confidence enums and
checksums. Upload rejects non-UTF-8, binary/archive, unsafe-name, MIME, size,
header-drift and prohibited commercial-column inputs before retaining bytes.
Parsing is bounded RFC 4180 and preserves physical record lines and row hashes.

Commit requires an exact job version and two distinct actors: a migration lead
and governance/business reviewer. Canonical effects retain file/job/row/hash,
represented time, actual recorded time, source, confidence and limitations.
Raw punches and daily attendance use an exclusive employee-day authority key,
so durations are never additive.
