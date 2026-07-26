# F03 Changelog

## 2026-07-26 — Provider-neutral local vertical

- Added delivery planning/version/baseline/approval, recorded Linear evidence,
  signed webhook and integration-health local paths.
- Added React planning, detail and health screens plus deterministic intercepted
  F03 browser-contract coverage.
- Hardened the local implementation through Flyway V8–V10 and focused review;
  all reviewed local P0 integrity findings are resolved by V10.
- Recorded evidence: 47 frontend tests, 49 backend Testcontainers tests and 26
  intercepted Playwright cases passing (including 8 F03/cross-feature cases).
- Preserved local P1 backlog and external Linear/mail/BFF gates. This release
  does not claim live provider connectivity or complete F03 delivery.

Related records: [FIXES.md](FIXES.md), [TEST_AUTOMATION.md](TEST_AUTOMATION.md)
and [POST_FIX_REVIEW.md](POST_FIX_REVIEW.md).
