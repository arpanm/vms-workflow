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

The 30 July focused closure command is:

```bash
mvn -B -f backend/pom.xml \
  -Dtest=MigrationCsvParserTest \
  -Dit.test=MigrationWorkflowIT verify
```

Result: **3/3 unit and 14/14 PostgreSQL integration tests passed**. The added
case proves a holiday row with a blank required name, malformed date and
malformed represented timestamp is staged as invalid with the exact stable
field codes. The root regression lane remains responsible for the combined
suite after all feature branches are integrated.

The independent-review rerun extended that result to **3/3 unit, 15/15
`MigrationWorkflowIT` and 1/1 `MigrationDomainAdapterIT` passed**. It covers
hash-only confirmation association, metadata-only invoice semantics,
conditional allocation approvals, deduplicated findings and local timestamp
plus IANA-zone commit behavior.
