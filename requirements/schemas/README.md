# Supporting Schemas and Machine-Readable Contracts

These files make the PRDs easier for Cursor/Claude to implement and test. They are **supporting drafts**, not a substitute for the normative Markdown requirements.

| File | Purpose |
|---|---|
| `canonical_event_envelope.schema.json` | Common envelope for workflow, integration and audit events. |
| `evidence_manifest.schema.json` | Deterministic monthly procurement evidence package manifest. |
| `linear_issue_snapshot.schema.json` | Point-in-time Linear issue evidence. |
| `attendance_policy.example.json` | Example configurable attendance policy; values require business approval. |
| `approval_policy.example.json` | Example reminders/escalation policy with no silent approval. |
| `integration_connection.example.json` | Secret-reference and capability-certification pattern. |
| `import_error_codes.json` | Stable validation/error codes for migration UI and reports. |
| `workflow_state_machines.json` | Canonical state-machine seed/validation input. |
| `api_contract_outline.yaml` | Provider-neutral API outline with idempotency and webhook requirements. |
| `proposed_schema_outline.sql` | Additive database design outline; review and split into production migrations. |

## Rules

- Validate all JSON files in CI.
- Generate typed validators from schemas where practical.
- Never put secrets in repository files or browser-visible configuration.
- Never add salary, CTC, markup, individual rates or payroll calculations to these contracts.
- Final migrations must include tested RLS and rollback procedures.
