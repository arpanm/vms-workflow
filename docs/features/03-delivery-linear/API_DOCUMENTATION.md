# F03 API Documentation

The executable F03 API is documented by protected springdoc OpenAPI. This guide
is the stable operator/developer summary; it does not expose a secret,
credential or provider token.

## Authorization and error boundary

All planning and administration endpoints require a bearer JWT plus active
organization/engagement/object permission. Unknown and inaccessible objects
share a sanitized `404`. Only the Linear webhook route is public; it validates
raw bytes and connection-scoped HMAC before any mutation.

| Area | Routes |
|---|---|
| Planning | `GET/POST /api/v1/delivery/plans`, detail, submit, approvals and revisions |
| Recorded Linear evidence | issue links, current evidence, snapshots and connection health under `/api/v1/integrations/linear` |
| Authorized processing | `POST /api/v1/integrations/linear/deliveries/{deliveryId}/process` |
| Public receiver | `POST /api/v1/integrations/linear/webhook/{connectionId}` |

The webhook requires `Linear-Signature`, `Linear-Timestamp`, `Linear-Delivery`
and a matching body timestamp/organization/connection. It persists and queues
validated delivery evidence before returning success. The local implementation
does not provide a background queue worker or live provider registration.

`COMPLETED`/Linear Done is execution projection only. It cannot accept,
certify, confirm, invoice or otherwise transition the business workflow.

For request/response fields, status codes and locally implemented limits, see
[API.md](API.md). OpenAPI coverage is tested locally; live OAuth, GraphQL and
mail operations are excluded from this contract.
