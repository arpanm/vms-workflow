# F06 Backend Test Automation

Focused unit automation covers all-template registry integrity and deterministic
RFC 4180 behavior including BOM, quoted commas/newlines, escaped quotes,
unterminated input and row bounds.

Run the complete backend gates from `backend/`:

```bash
mvn test
mvn verify
```

The existing integration profile applies all Flyway migrations plus synthetic
security fixtures to Testcontainers PostgreSQL. F06 integration coverage should
exercise upload, validation, duplicate classification, dual approval, commit,
reconciliation, compensation/rollback denial, retro request timestamps and
cross-tenant authorization.
