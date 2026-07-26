# F03 security analysis

**Decision:** P0 security blockers remain. Do not expose the F03 webhook or treat its callback/authentication model as production-ready.

## P0 — Tenant isolation and authentication

1. **Global webhook secret defeats connection isolation.** `LinearIntegrationService` uses `vms.linear.webhook-secret` for all HMAC validation while only checking that a connection has a reference ([LinearIntegrationService.java](../../../backend/src/main/java/com/vms/workflow/application/LinearIntegrationService.java#L47), [LinearIntegrationService.java](../../../backend/src/main/java/com/vms/workflow/application/LinearIntegrationService.java#L257), [LinearIntegrationService.java](../../../backend/src/main/java/com/vms/workflow/application/LinearIntegrationService.java#L580)). Retrieve per-connection secret material server-side, support rotation, scope cache keys, and test cross-connection rejection.

2. **Approval authority is not a trustworthy scoped snapshot.** Caller-selected approvers receive `{"configured":true}` ([DeliveryPlanningService.java](../../../backend/src/main/java/com/vms/workflow/application/DeliveryPlanningService.java#L523)); subsequent vote authorization is dynamic only and self-approval checks only creator. Snapshot real org/engagement/project scope, roles, policy version and conflicts at submit, and enforce vendor/product/coordinator restrictions.

3. **Frozen evidence integrity is not database-enforced.** Frozen records can be changed by direct SQL or the webhook projection update. This is a security/audit concern, not merely a workflow issue. Use least-privilege DB roles plus write-protection triggers and immutable payload snapshots.

## P1 — Public callback hardening

The route is correctly isolated as `permitAll` ([SecurityConfig.java](../../../backend/src/main/java/com/vms/workflow/security/SecurityConfig.java#L30)), receives raw bytes, checks HMAC in constant time, checks timestamp/body organization/connection, and inserts before acknowledging. Retain those properties. Add perimeter request-size limits before `byte[]` binding, strict content type/encoding policy, rate/concurrency limits, bounded error responses, audit counters without raw secrets/payload logs, and connection status/revocation checks. Dedupe currently uses delivery UUID primary key and hash fingerprint, but conflict handling returns `duplicate=true` for any insert conflict without confirming the returned delivery belongs to the same connection/payload ([LinearIntegrationService.java](../../../backend/src/main/java/com/vms/workflow/application/LinearIntegrationService.java#L289)); verify collision/reuse semantics and raise a sanitized conflict for mismatched reuse.

Webhook receipt and processing are separated by rows but operationally processing is still an authenticated request. Run untrusted provider data in a bounded worker, validate event schema before projection, claim safely, record attempts, and quarantine invalid events. Apply provider `updatedAt` ordering to prevent a delayed valid event regressing current state.

## P1 — Link, data and output security

The browser can submit issue UUID/title/state/URL for a requested connection. The URL host allowlist does not establish that the issue belongs to the connection's Linear organization/team. Require a server-side adapter resolution using connection credentials and allowlisted fields; validate connection scope as well as deliverable scope. Do not request/store descriptions, assets, comments, people or files unless separately approved.

Recipient values and all free text should have maximum lengths and validation. HTML rendering escapes five basic characters ([DeliveryPlanningService.java](../../../backend/src/main/java/com/vms/workflow/application/DeliveryPlanningService.java#L1026)), which is a useful start, but sender/template/recipient controls, header injection safety, audit redaction, and log/exception review are absent. Do not log raw callback bodies or secret references.

## P2 — Exposure and documentation

OpenAPI is authenticated and test asserts secret field names/fixture secret are absent, which is good. Add authorization tests for docs, schema scans for all secret/token/PKCE names, and confirm error messages/correlation IDs do not reveal connection, organization, contact, or provider details. Keep frontend route guards as usability only: all authorization decisions must remain server/DB enforced.

## Production prerequisites

Live OAuth/GraphQL/webhook/email remains an external authorization gate: Reliance must approve the OAuth app/app actor, tenant workspace/team/scopes, secret-manager references and registered callback; mail owner must approve provider, sender, mailbox, recipient groups, retention and sandbox/live testing. Until then use recorded fixtures and a fake mail adapter; no personal keys, global fixtures, credentials, webhook secret, verifier, or token may enter source, browser storage, ordinary DB columns, Swagger, test artifacts, or logs.
