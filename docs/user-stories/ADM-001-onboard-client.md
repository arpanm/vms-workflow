# ADM-001 — Onboard a client and delivery scope

**Persona:** Platform/ArrowFoundry administrator
**Implementation:** IMPLEMENTED — V46, `POST /api/v1/collaboration/clients`, `/administration/clients`
**Testing:** AUTOMATED — `ClientCollaborationIT`; execution in [TEST_EXECUTION.md](TEST_EXECUTION.md)

## Description and UI flow

Create an active client organization, one engagement, its initial project and
thirteen monthly planning scopes from the selected vendor organization.
Open **Administration → Client onboarding**, enter legal/display/domain and
delivery-scope fields, then submit.

## Acceptance criteria

- A caller without `client.onboard` is denied without disclosing target data.
- Organization, engagement, project and months commit atomically and are
  tenant-linked; duplicate codes fail with conflict.
- The new engagement is immediately selectable on client dashboards.

## Test cases

- Authorized onboarding returns all created identifiers and 13 months.
- Cross-role denial and duplicate-code rollback leave no partial client.
