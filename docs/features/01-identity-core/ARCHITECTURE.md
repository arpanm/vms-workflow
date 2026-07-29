# F01 Identity and Core Administration Architecture

The browser reads session permissions and active organization, engagement and
month scope. It never grants authority. Every `/api/v1/core/**` mutation is
authenticated by Spring Security and resolved through active, effective-dated
membership plus organization/engagement/project role assignments.

`CoreAdministrationService` owns validation, optimistic versions, typed domain
conflicts, audit creation and workflow evaluation. V34 independently enforces
same-scope references, effective-window integrity, immutable published
configuration/policy versions, delegation bounds, approval current-stage and
authority identity, guarded request transitions, authoritative reopen dispatch,
and append-only month transition/audit evidence. Runtime roles cannot alter
immutable policy/configuration evidence, stage snapshots, approval actions or
transition history. The service may advance an approval request, but V34 binds
that update to one immutable action, the captured request version and the
snapshotted quorum; unbound identity/evidence/state changes and deletion fail.

Approval flow is:

1. An administrator creates a draft or creates the next draft revision under a
   stable policy identity, then publishes an effective immutable version with
   ordered stages. A future-effective revision does not disable the current
   effective version: publication closes the prior effective window immediately
   before the new window and both immutable records remain queryable.
2. An authorized creator supplies a published engagement-scoped `REOPEN`
   policy, a `REOPEN_REQUESTED` month ID and an idempotency key. The server
   resolves the month version/hash/scope and required permission.
3. Request creation snapshots every stage's eligible authority IDs,
   contact-group version, quorum and delegation rule. `ALL` is derived from the
   request-time eligible set, so later membership changes cannot weaken it.
4. A snapshotted authority or bounded delegate acts on the exact current stage
   with an actor-scoped idempotency key.
5. When the captured policy requires evidence, a nonblank reason is mandatory.
   Evidence captures the actor and original delegated-from authority. Quorum
   counts that authority once. Self-approval compares the requester with the
   original authority, preventing a delegated bypass.
6. The final approval atomically marks the request approved and advances the
   bound month from `REOPEN_REQUESTED` to `REOPENED`. Database guards reject
   direct request mutation and a reopen lacking its approved request.
7. Evidence and snapshots are immutable; stale versions, duplicate authority
   votes and mismatched idempotent replays conflict.

Existing F03–F06 workflow evidence is not rewritten. F01 supplies policy
administration and the authoritative governed-reopen execution path.
Specialized action types continue to use their domain engines; the public F01
request endpoint rejects them rather than pretending to dispatch them.

Local validation uses generated OpenAPI plus Spring MockMvc and Testcontainers
PostgreSQL 18.4 migrated from V1 through V34. Provider-backed OIDC/BFF,
provisioning and key-rotation acceptance is a separate controlled-environment
gate.
