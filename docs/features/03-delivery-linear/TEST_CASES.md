# F03 — Planning and Linear Test Cases

- `T-PLAN-001/002`: incomplete deliverables cannot submit; complete plans route to eligible approvers.
- `T-PLAN-003–005`: quorum, self-approval restriction, freeze, revision and immutable prior baseline.
- `T-MSG-001/002`: commitment recipients/CC and delivery attempts are validated and retained.
- `T-LIN-001`: supported issue identifiers resolve to immutable issue UUIDs.
- `T-LIN-002/003`: OAuth state/PKCE and secrets never reach browser/storage columns.
- `T-LIN-004/005`: invalid, stale or replayed webhook signatures fail without mutation.
- `T-LIN-006`: duplicate deliveries are idempotent.
- `T-LIN-007`: plan/month-end snapshots retain historical state after current issue changes.
- `T-LIN-008/009`: missed events reconcile and health/staleness is visible.
- `T-LIN-010`: `Done` updates progress only; no certification action is generated.
- `E2E-03`: approved plan through issue execution evidence and month-end snapshot.
