# F07 — Hardening and Go-live Test Cases

- Full role × scope × Spring API/service × PostgreSQL-role/storage matrix, including disabled identities and direct-database bypass attempts.
- Load tests for attendance peak, dashboard/report reads, webhook duplicates, import batches and deterministic package generation.
- Accessibility tests for login, self attendance, plan approval, certification, confirmation and invoice review.
- Backup/restore drill verifies database rows, private object metadata and package hashes.
- Failure injection covers provider outage, retry exhaustion, outbox lag, stale status and partial import/package failure.
- Security suite covers secret scanning, vulnerable dependencies, SSRF allowlists, XSS content, CSRF, replay, malicious files and export formula injection.
- `T-DR-001`: documented recovery meets approved RPO/RTO and preserves immutable evidence lineage.
- `E2E-10`: canary rollout, monitoring alert, rollback and post-rollback integrity verification.
