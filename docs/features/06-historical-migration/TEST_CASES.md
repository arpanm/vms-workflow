# F06 — Historical Migration Test Cases

- `T-MIG-001`: dry-run validates schema version, required fields, references and rows without canonical mutation.
- `T-MIG-002`: same batch/idempotency keys replay without duplicates.
- `T-MIG-003`: dependency failures quarantine rows with stable error codes and safe downloadable report.
- `T-MIG-004`: retro approval/confirmation retains represented date plus actual recorded/actor/source timestamps.
- `T-MIG-005`: correction/reopen supersedes evidence and never mutates the original package.
- Template tests cover all 13 supplied templates, manifest checksums and prohibited fields.
- Security tests cover formula injection, oversized/malformed files, cross-tenant identifiers and unauthorized imports.
- `E2E-08`: dry-run, correction, publish, retro confirmation and historical close from 1 June 2026.
