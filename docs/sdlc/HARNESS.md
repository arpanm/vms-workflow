# Agentic SDLC Harness

## Purpose

This harness makes every feature follow the same evidence-producing delivery sequence. It is a workflow contract, not a claim that later phases have been implemented.

## Mandatory sequence

1. Create `TASKS.md` with requirement IDs, dependencies, acceptance criteria, files, migrations, rollback and definition of done.
2. Create `TEST_CASES.md` before production code.
3. Record implementation scope and traceability in `CODEGEN.md`.
4. Review implementation with the configured review model and record findings in `CODE_REVIEW.md` and `CODE_ISSUES.md`.
5. Generate automated tests (JUnit/Spring/Testcontainers for backend and frontend tests where applicable) and record coverage in `TEST_AUTOMATION.md`.
6. Review tests and record findings in `TEST_REVIEW.md` and `TEST_ISSUES.md`.
7. Run static/domain/data-integrity analysis and record it in `CODE_ANALYSIS.md`.
8. Run threat/authorization/PostgreSQL-role/secrets/privacy analysis and record it in `SECURITY_ANALYSIS.md`.
9. Fix accepted implementation, test, analysis and security issues; record disposition and verification in `FIXES.md`.
10. Add API/code documentation in `API_DOCUMENTATION.md` and Swagger/OpenAPI where APIs exist. Add UI flow/use documentation in `UI_DOCUMENTATION.md`.
11. Add task/change/architecture notes in `CHANGELOG.md` and cross-link the feature from the root `README.md`.
12. Pass the configured quality commands, review the final diff and create a local commit. Never push as part of this harness.

## Model separation

- Code generation: `gpt-5.6-sol`
- Reviews, analysis and security: `gpt-5.6-terra`
- Documentation: `gpt-5.6-terra`

The configuration validator rejects the harness when code generation and review use the same model.

## Gates

- Planned features require `TASKS.md` and `TEST_CASES.md`.
- Completed features require every artifact listed in `sdlc/harness.config.json`.
- Phase 2 cannot start until authenticated cross-tenant Java API/service authorization is executed against Flyway-migrated Testcontainers PostgreSQL and evidenced in approved staging.
- A finding is never silently dropped: it is fixed, explicitly accepted with owner/reason, or blocks the feature.
- Generated Spring OpenAPI/Swagger must match executable controllers and authorization rules.
- Review documents must name the reviewed commit/diff and model role.

## Commands

```bash
npm run sdlc:status
npm run sdlc:check
npm run typecheck && npm run lint && npm run test && npm run build
mvn -B -f backend/pom.xml verify
```

## Prompt contract

For a new prompt, first map the request to one or more `Fxx` features. If it creates a new feature, add it to `sdlc/harness.config.json` and create its planning artifacts before changing application code. Production backend work uses Java 25, Spring Boot 4.1.0, Maven, springdoc 3.0.3, PostgreSQL, JWT/OIDC, Flyway, and Testcontainers per requirement 22; the historical Supabase prototype is not a destination for new domains. Resume from the first incomplete artifact; never overwrite prior review evidence.
