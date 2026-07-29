# F07 local retention, privacy and legal-hold controls

This subsystem is local engineering evidence for F07-T036–T037 and
F07-T043–T044. It does not establish statutory retention periods, legal
authority, production immutable archives or provider deletion evidence.

## Retention schedules and execution

No retention duration is seeded. An active organization-scoped principal with
`retention.schedule.manage` must version a schedule and cite a policy
reference. The supported local record classes are:

- `TEMPORARY_EXPORT_CAPABILITY`
- `TEMPORARY_PACKAGE_SHARE`

A dry run uses the effective schedule and an explicit `asOf` timestamp. It
appends a run, candidate rows and a `DRY_RUN_COMPLETE` transition. Candidate
decisions distinguish due capabilities, future deadlines and artifacts under
legal hold.

Execution is resumable by attempt. Each candidate appends an execution result;
successful expiry appends a unique proof. Partial failures produce
`RETRY_SCHEDULED`, then `DEAD_LETTER` after the configured bounded attempt
limit. A new idempotency key resumes the same run and existing proof prevents a
duplicate effect.

Execution only changes temporary access:

- a ready report export becomes `EXPIRED`;
- a package share receives a revocation timestamp and actor.

The export artifact, package, package item, invoice and other closed evidence
remain stored and immutable. Proof rows explicitly record
`contentDeleted=false` and `closedEvidencePreserved=true`. Physical content
deletion, approved archive deletion and statutory schedules remain outside
this local control.

Configuration:

```text
VMS_RETENTION_TWO_PERSON_RELEASE=true
VMS_RETENTION_MAX_ATTEMPTS=3
VMS_RETENTION_RETRY_DELAY=PT5M
```

## Legal hold

Legal holds are scoped through the artifact owner organization and active
`legal-hold.manage` authority. Placement appends both the F07 hold/transition
evidence and the existing F05 artifact transition required by the PostgreSQL
artifact guard.

When two-person release is enabled, release first appends
`RELEASE_REQUESTED`. A different active authorized actor must append
`RELEASE_APPROVED`; the database trigger independently rejects self-approval.
Only then does the existing guarded artifact flag change. All F07 hold rows and
transitions reject update/delete directly in PostgreSQL.

## Classification and prohibited data

`f07_data_classification_inventory` covers tables, APIs, logs, exports,
artifacts and prohibited fields using `INTERNAL`, `CONFIDENTIAL` and
`RESTRICTED`. It records handling rules without placing sensitive values in the
inventory.

Salary, commercial rate and markup fields are explicitly prohibited.
`f07_assert_no_commercial_fields(jsonb)` recursively rejects these keys, and a
constraint applies it to finance export filters before persistence. Existing
DTO/template allowlists remain additional boundaries.

## API and authorization

All endpoints are below `/api/v1/governance/retention` and remain bearer
authenticated. Mutations require `Idempotency-Key`; organization permissions
are derived server-side from active principal, membership, role assignment,
role and permission records.

The local permission split is:

- `retention.schedule.manage`
- `retention.execute`
- `legal-hold.manage`

Cross-organization identifiers fail authorization. Artifact/hold mismatches
return non-enumerable not-found behavior.

## Local verification

Non-Docker checks:

```text
cd backend
mvn -q -DskipTests test-compile
mvn -q -Dtest=RetentionPrivacyServiceTest test
```

Isolated Testcontainers coverage:

```text
cd backend
mvn -q -Dit.test=F07RetentionPrivacyIT \
  failsafe:integration-test failsafe:verify
```

The integration test covers no seeded durations, schedule/dry-run scoping,
cross-tenant denial, two-person release, direct-SQL append-only enforcement,
classification inventory, and prohibited commercial-field rejection. It is
local synthetic evidence, not production/legal acceptance.
