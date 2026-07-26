# F02 security analysis

> Historical pre-fix security analysis followed by a dated current disposition.

## Assessment

Tenant object non-disclosure is generally implemented correctly, but F02 has
two P1 authorization/integrity defects that block release: review can create
employee attendance events, and employee self-service cannot use its intended
least-privilege role.

## P1 — Attendance review is not read-only

`attendance.review` is accepted by `requireAttendanceAccess`
(`WorkforceAuthorizationService.java:56-63`), then reused for POST punches and
regularizations (`AttendanceService.java:47-49`, `152-154`). A reviewer can
therefore create an immutable record for another employee. Immutable storage is
not a mitigation when the wrong actor was authorized to create the record.

Remediate with separate query and command permissions, self linkage for ordinary
punch, and a separately auditable, dual-controlled correction flow for any
cross-employee change.

## P1 — Capability gate is only assignment-time validation

The migration checks same-tenant certified capability when a source assignment
is inserted or edited (`V5__workforce_capability_cutoff_and_reopen.sql:21-55`).
It does not protect existing greytHR authority after certification revocation.
The application has no provider integration in scope, which is safer than a
partial provider call, but the persisted authoritative mode must become
unavailable/disabled when its certification ceases to be valid.

## Tenant isolation observations

- Employee organization is looked up before an object-scope decision, but an
  inaccessible/missing object returns the same 404 (`WorkforceAuthorizationService.java:39-63`,
  `108-115`).
- Source assignment capability validation ties the certification organization to
  the employee organization (`V5__workforce_capability_cutoff_and_reopen.sql:23-29`),
  which prevents cross-tenant certification reuse at assignment time.
- Allocation database integrity limits an employee to an organization
  participating in an engagement (`V4__workforce_attendance.sql:91-145`), a
  valuable data-layer defense. Authorization tests should nevertheless cover
  read/write requests at organization, engagement and project scopes.
- The frontend feature flag is not authorization. API endpoints remain exposed
  whenever a caller has the corresponding backend permission.

## Priority hardening plan

1. Separate attendance query, self-command, reviewer and administrative
   correction permissions; add deny tests for all POST paths.
2. Add a self employee identity endpoint and remove roster reads from
   self-service flows.
3. Make certification revocation atomically deactivate authority and audit the
   transition; test same-tenant, cross-tenant and revoke cases.
4. Make GET read-only, use explicit recalculation jobs, and require deterministic
   roster completeness before close.
5. Add contract-backed/full-stack browser tests; fixture-only tests must not be
   reported as backend or security validation.

## Post-fix security disposition — 2026-07-26

The two original P1 security findings are fixed in the bounded implementation.
Self commands call `requireAttendanceSelf`, which requires an active/enabled
effective employee link and `attendance.self`; reviewers retain read access
only. `/employees/me` requires exactly one authorized active link and returns
the same sanitized 404 for no/ambiguous access. Integration tests deny reviewer
punch, leave and regularization submission and verify missing IDs remain
non-disclosing.

Effective source evaluation now joins the capability certification and returns
conflict when greytHR authority is no longer same-organization and certified.
This is tested after revocation. It is a fail-closed boundary, not a substitute
for the absent credential/cutover/reconciliation workflow.

The UI feature flag is not a security control and there is still no API-wide
rollout kill switch. Broader role/project authorization matrices and real
provider/BFF/full-stack testing remain hardening work. See
[FIXES.md](FIXES.md), [API_DOCUMENTATION.md](API_DOCUMENTATION.md) and
[UI_DOCUMENTATION.md](UI_DOCUMENTATION.md).
