# F00 — Foundation Test Cases

| ID | Level | Scenario | Expected |
|---|---|---|---|
| F00-UT-001 | Unit | Read flags with no overrides in development | Legacy on; new domains off |
| F00-UT-002 | Unit | Set legacy `VITE_DEMO_MODE=true` in production | Legacy validation rejects startup; Java runtime does not use browser demo state |
| F00-UT-003 | Unit | Use non-boolean flag value | Typed validation rejects it |
| F00-UT-004 | Unit | Omit a target Java JWT/database/provider configuration value | Startup error identifies missing key without leaking values |
| F00-INT-001 | Script | Run `npm run sdlc:check` | Every planned feature has tasks/tests and model separation passes |
| F00-REG-001 | Build | Build the root Vite React/TanStack UI and run Java backend verification when scaffolded | UI compiles; Java target verifies with Flyway/Testcontainers |
| F00-REG-002 | Smoke | Exercise Java health/auth endpoint in staging-like environment; inspect historical baseline only through approved rollback artifact | Java surface reports intentional auth gate; no anonymous business access |
| F00-SEC-001 | Static | Search tracked files/build output for database/JWT/provider secrets | No server-only credential is exposed |
| F00-OPS-001 | Manual | Execute documented rollback rehearsal in staging | Baseline restores and PostgreSQL backup/restore evidence is preserved |

F00-OPS-001 and the live smoke portion of F00-REG-002 require configured, user-approved staging systems and must not be marked passed based on source inspection alone.
