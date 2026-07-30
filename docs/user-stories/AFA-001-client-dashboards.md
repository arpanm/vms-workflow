# AFA-001 — Browse clients and client dashboards

**Persona:** ArrowFoundry administrator
**Implementation:** IMPLEMENTED — organization/engagement scope selector and dashboards
**Testing:** VERIFIED — core administration integration/browser suites

## Description and UI flow

Select an authorized client organization and engagement from the global scope
selector; dashboard, tasks, workforce, certification and finance pages reload
within that scope.

## Acceptance criteria

- Only engagements in active memberships/role assignments are listed.
- Scope changes invalidate cached tenant data.
- Direct foreign IDs return non-disclosing not-found responses.

## Test cases

- Switch between two authorized clients without data bleed.
- Deny an unassigned client and clear stale persisted scope.
