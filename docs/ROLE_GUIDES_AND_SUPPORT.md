# Role Guides and Support

The in-application **Role guides & support** workspace is the primary starting
point for role-specific help. It links each persona to the correct workflow and
keeps escalation guidance beside the product rather than relying on tribal
knowledge.

## Shared support rules

- Never share passwords, access tokens, secure-action links, raw email,
  restricted artifacts, payroll/commercial data or personal data with support.
- Share the visible correlation ID, scoped record ID, action time and safe error
  code.
- Refresh before retrying a version conflict. Reuse an idempotency key only for
  the same intended command.
- Never bypass readiness, scan, authority, separation-of-duties, legal-hold or
  immutable-history controls.
- Provider delivery/read state and elapsed time never imply business approval.

## Employee

Use **Today**, **Leave** and **Regularizations**. Ask vendor HR to correct roster,
calendar, employment-window or leave-balance facts. Do not ask an operator to
rewrite attendance history.

## Vendor HR / administrator

Use **Employees**, **Workforce administration** and **Month status**. Resolve
allocations, calendars, policies, roster completeness and attendance blockers
before month close. Escalate provider synchronization with the correlation ID
and affected source/effective dates.

## Vendor delivery

Use **Delivery plans** and **Certification** to plan and submit delivery facts.
Frozen plans and submitted evidence are immutable; use a revision,
clarification or governed reopen.

## Product owner

Use **Certification** and **Confirmation**. Review the exact baseline, evidence
and version before recording an explicit decision. Missing evidence,
conflicting actions and separation-of-duties exceptions go to governance.

## Procurement / finance

Use **Finance workspace**, **Procurement** and **Finance reports**. Confirm
package/invoice lineage, scan state, masking and confirmation readiness. Apply a
hold or exception rather than approving unclear evidence.

## Integration administrator

Use **Linear health** and the relevant operations view. During an outage, keep
provider facts explicitly stale, use bounded replay/reconciliation and verify
business-effect counts before recovery is closed.

## Governance / reopen

Use **Approval inbox**, **Policies & delegations** and **Month governance**.
Decide only within current scoped authority; reject self-approval, ambiguous
scope and incomplete impact/risk declarations.

## Migration operator

Use **Historical migration**. Stage, scan, validate, reconcile and commit with
the required distinct authorities. Pause or cancel unsafe work and retain the
batch, report and correlation ID for investigation.

## Operator references

- [F07 incident runbooks](operations/F07-RUNBOOKS.md)
- [Release and disaster recovery](operations/F07-RELEASE-AND-DR.md)
- [Consolidated feature status](FEATURE_STATUS.md)
- [Consolidated pending work](PENDING_WORK.md)
- [End-to-end regression catalog](testing/E2E_REGRESSION_CASES.md)
