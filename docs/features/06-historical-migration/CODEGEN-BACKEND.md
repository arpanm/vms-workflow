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

Each registry entry also owns its exact required-field list. The validation
service applies that schema before referential/domain validation, and performs
common ISO date, `YYYY-MM` month, offset timestamp, email and SHA-256 checks.
Consequently an incomplete row cannot be approved as valid and then fail only
after commit begins; it remains staged with stable, redacted field findings.
Conditional fields remain conditional: supersession evidence, approval actors
and represented decision times are required only by their governing state.
When an invoice sample has no document SHA-256, its metadata-only version is
explicitly labelled `UNVERIFIED_METADATA_ONLY`; the service never represents a
derived metadata fingerprint as a supplied document hash.
