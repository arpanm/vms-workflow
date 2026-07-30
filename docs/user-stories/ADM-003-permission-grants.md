# ADM-003 — Grant scoped action permissions

**Persona:** Platform/ArrowFoundry administrator
**Implementation:** IMPLEMENTED — role templates plus organization/engagement/project grants
**Testing:** AUTOMATED — RBAC integration assertions; [execution](TEST_EXECUTION.md)

## Description and UI flow

Grant a client user an approved role at organization, engagement or project
scope. The UI shows the resulting action-permission count and server-derived
role list.

## Acceptance criteria

- Only `client.user.manage` may grant roles.
- The scope must belong to that client; forged cross-client scope IDs fail.
- Permissions come only from active role mappings and effective assignments.

## Test cases

- Grant product-owner and approver roles and verify distinct capabilities.
- Reject cross-tenant project grant and unknown role code.
