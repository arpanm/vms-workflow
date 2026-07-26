# F03 code issues

This is the actionable implementation backlog derived from the independent review. P0 items block release.

| Priority | Evidence | Issue | Required fix |
|---|---|---|---|
| P0 | `V7__delivery_planning_linear.sql:412-432`; `LinearIntegrationService.java:496-518` | Frozen/superseded plan-owned data is mutable; webhook updates a frozen deliverable row. | Database immutability triggers for all frozen version content, or separate live projection table; immutable frozen snapshot and permitted-column policy. |
| P0 | `DeliveryPlanningService.java:192-215,523-531` | Self-approval protection covers only creator; configured approvers and authority snapshots are caller controlled/static. | Resolve/snapshot active scoped approvers and conflicts at submit; enforce policy for creator, coordinator, vendor/product owners and configured exceptions. |
| P0 | `LinearIntegrationService.java:47-57,547-599` | A global property verifies every connection despite per-connection secret references. | Secret-manager dereference by connection, rotation/revocation support, no production global fallback. |
| P1 | `LinearIntegrationService.java:60-124`; `V7...sql:385-410` | Link creation does not authorize the submitted connection or verify issue workspace/team ownership; client can assert issue metadata. | Require connection scope, resolve via server adapter/fixture, validate organization/team, retain inaccessible/broken status, require multi-link rationale. |
| P1 | `DeliveryPlanningService.java:849-899` | Signed checksum excludes dependencies, configured approvers and authority. | Version and serialize a sorted canonical document with all commitment fields and snapshot IDs. |
| P1 | `DeliveryPlanningService.java:382-482` | Completeness accepts any nonblank link exception and does not validate contacts, active link quality, month/date or exception authority. | Add policy-backed validation/blockers and persist authority/audit facts for exceptions. |
| P1 | `LinearIntegrationService.java:314-425` | Queue processing occurs on an interactive API request; no worker claiming/retry/dead-letter policy exists. | Implement worker with bounded retries/backoff, `SKIP LOCKED`, attempt records and operator replay enqueue. |
| P1 | `SecurityConfig.java:30`; `LinearIntegrationController.java:74-93` | Public endpoint has no perimeter size/rate protections and handler materializes body before its limit check. | Configure request limits/rate limit/content-type handling and sanitized constant-shape callback failures. |
| P1 | `DeliveryPlanningService.java:247-280` | Revision changes old frozen state to `SUPERSEDED` and copies evidence but produces no field/add/remove diff or effective-baseline lineage. | Preserve original frozen state; store revision relation/diff and baseline lineage at clone/freeze. |
| P2 | `V7...sql:9-40,109-120,327-357` | State transitions, dependency target ownership and queue job lifecycle are not constrained in SQL. | Add check/trigger constraints and migration tests for invalid updates. |
| P2 | `LinearIntegrationService.java:395-410` | A late/out-of-order provider event can overwrite current state; `updatedAt` is stored but not compared. | Compare provider update timestamps/version, ignore/quarantine stale events and audit the decision. |
| P2 | `DeliveryDtos.java:18-84` | DTO size/pattern/email/UUID semantic limits are weak; unbounded text flows into HTML/outbox. | Add length, enum, date and recipient-address/subject constraints plus output/audit redaction. |

## Required acceptance evidence

Before release, demonstrate: frozen-content SQL mutation fails; every conflict/quorum case fails or succeeds correctly under concurrency; a secret for connection A cannot sign for B; cross-organization/team issue linking fails; duplicate/out-of-order callback processing is idempotent; and `COMPLETED` changes no acceptance/certification/month business state.

## Post-fix disposition — 2026-07-26 (pre-V9 re-review)

The detailed evidence is in `POST_FIX_REVIEW.md`. This disposition was written
against V7/V8. A subsequent
`V9__delivery_lineage_and_transition_integrity.sql` plus focused integration
test now targets the original evidence-integrity blocker, so its current status
is **FIXED PENDING FOCUSED V9 RE-REVIEW**, not silently closed.
That interim label is superseded by the V9 focused re-review at the end of this
file.

| Original issue | Disposition | Residual action |
|---|---|---|
| P0 frozen/superseded content mutable | **FIXED PENDING FOCUSED V9 RE-REVIEW** | V7/V8 left `delivery_deliverables.deliverable_code/plan_id` and transition columns unguarded. V9 and a new integration test target those exact paths; verify before closure. |
| P0 incomplete SOD/authority snapshot | **SUBSTANTIALLY FIXED** | Scoped assignments and conflicts are resolved/snapshotted server-side and DB-gated. Finish `N_OF_M`, stale/inactive/out-of-scope and rejection evidence. |
| P0 application-wide webhook secret | **FIXED FOR LOCAL VERTICAL** | Per-reference current/previous keys and cross-connection rejection are tested. Production secret-manager key retirement/revocation remains an external gate. |
| P1 untrusted link ownership/metadata | **FIXED FOR RECORDED ADAPTER** | Server fixture metadata is org/team checked and multi-link rationale is enforced. Live GraphQL ownership remains external. |
| P1 incomplete canonical checksum | **PARTIAL** | Dependencies, authority, recipients, links and snapshots are included; prior version, revision reason/impact and plan/month identity are not. |
| P1 incomplete submission validation | **PARTIAL** | Link quality/date/allocation/dependency/subject checks improved; exception authority, controlled contact groups and approved no-deliverables workflow remain. |
| P1 interactive queue processor | **OPEN** | Implement an autonomous claimant, leases, retry/backoff/dead-letter and replay-enqueue semantics. |
| P1 callback perimeter limits | **PARTIAL** | JSON content type and post-allocation size check exist; pre-allocation/decompression/rate/concurrency controls do not. |
| P1 revision rewrites old evidence/no diff | **PARTIAL** | Old baseline stays frozen and lineage is retained; editable revision, field/add/remove diff and effective-baseline comparison are absent. |
| P2 weak SQL lifecycle/dependency/queue constraints | **PARTIAL; transition/lineage portion pending V9 re-review** | V9 targets the explicit transition matrix/stable deliverable guard. Dependency ownership and queue transition rules remain open. |
| P2 out-of-order event regression | **FIXED** | Stale events are retained/audited and cannot regress current state/projection. Define equal-timestamp semantics. |
| P2 weak DTO bounds/semantics | **SUBSTANTIALLY FIXED** | Request bounds/enums/email validation improved; finish explicit OpenAPI response nullability/enums and generated contract validation. |

Additional local issues found post-fix: the immutable `commitment_outbox` row
also owns mutable delivery status, no fake/live mail sender exists, a
same-fingerprint/different-delivery webhook is a generic constraint conflict,
and the documented state/rework/revision-diff flow remains incomplete.

## V9 focused re-review — 2026-07-26

**P0 disposition: PARTIALLY RESOLVED; LOCAL RELEASE STILL BLOCKING.**

V9 correctly guards stable deliverable updates/deletes and replaces the loose
V8 version trigger with an explicit transition/identity/timestamp matrix. The
new focused test covers those original mutation shapes, and the implementation
lead recorded 46/46 backend tests passing.

Three release-critical database paths remain:

1. `PENDING_APPROVAL -> FROZEN` checks column shape but not approval quorum,
   baseline, snapshot/outbox or audit existence (`V9:121-143`). A correct-shape
   direct update freezes a zero-approval version; the new test only tries
   freezes with an additional invalid mutation before using the approved service
   path.
2. V8 child guards still exclude `REJECTED` from
   `protected_delivery_version_state()` (`V8:197-207`), so signed rejected child
   content remains mutable/deletable.
3. No database gate requires a `delivery_deliverable_versions` row's stable
   `delivery_deliverables.plan_id` to equal its
   `delivery_plan_versions.plan_id` on insert/update.

Add these constraints and direct SQL tests before closing the P0 local integrity
gate.

All existing P1 residuals remain **local blockers for the complete scoped
provider-neutral vertical**: worker/retry/dead-letter/replay, callback perimeter,
approved exceptions/contact resolution, editable revision/diff/state rework,
complete revision checksum, fake-mail attempts/outbox transitions and atomic
fingerprint dedupe. Live Linear/mail/full-stack acceptance remains external and
blocks full production F03, but does not reclassify those local gaps.

## V10 focused re-review — 2026-07-26

**P0 disposition: RESOLVED. All identified local P0 integrity blockers for the
scoped provider-neutral vertical are closed.**

- V10 requires eligible checksum-matching quorum, no reject vote, a matching
  baseline/deliverable count, matching idempotent outbox and attributable
  `PLAN_FROZEN` audit before `PENDING_APPROVAL -> FROZEN`
  (`V10:108-213`).
- Freeze now writes baseline, outbox and audit before the final state update,
  within the existing `@Transactional` approval method and locked current
  version (`DeliveryPlanningService.java:185-239,281-326`). A failed terminal
  update rolls the unit back.
- `REJECTED` is included in the shared protected-state function, and rejection
  requires a matching signed reject vote (`V10:4-17,130-142`).
- Migration preflight rejects existing cross-plan deliverable/version lineage;
  the new insert/update trigger enforces equal plan ownership going forward
  (`V10:221-262`).
- Focused tests cover each missing-evidence freeze stage, legitimate complete
  freeze, unsigned versus signed rejection with immutable children, and
  cross-plan insert/move versus same-plan clone. Concurrent `ALL` quorum still
  creates one baseline and one outbox. The recorded full Maven result is 49/49.

An injected post-evidence freeze failure would strengthen rollback test evidence
but is not a remaining P0 given the inspected transaction boundary and uncaught
database failure behavior.

All previously documented P1 items remain **local blockers for the complete
scoped provider-neutral vertical**. Live Linear/mail/full-stack gates remain
external blockers for full production F03; V10 does not reclassify them.
