# F01 — Identity and Core Test Cases

| ID | Source | Scenario | Expected |
|---|---|---|---|
| T-IAM-001 | PRD 16 | Valid invited user signs in | Session resolves to active profile/membership |
| T-IAM-002 | PRD 03 | Unauthenticated browser/direct Java REST query | No business row returned or mutated |
| T-IAM-003 | RQ-001 | Org A member reads Org A master data | Allowed within granted scope |
| T-IAM-004 | PRD 03 | Org A member reads Org B data by forged UUID | Denied through Spring authorization and PostgreSQL-backed integration test |
| T-IAM-005 | PRD 03 | Project A owner reads Project B-only object | Denied |
| T-IAM-006 | PRD 03 | Disabled member reuses a valid session | Protected request denied |
| T-IAM-007 | RQ-033 | Change local demo role in production | Authorization unchanged; switcher absent |
| T-IAM-008 | PRD 03 | Vendor submits a client certification mutation | Denied even with forged payload |
| T-IAM-009 | PRD 03 | Service account records business confirmation | Denied |
| T-IAM-010 | RQ-034 | Legacy flag on/off | Old routes operate when on and are intentionally unavailable when off |
| T-CORE-001 | PRD 02 | Client and vendor organization are identical | Constraint rejects engagement |
| T-CORE-002 | PRD 02 | Month date is not first of month | Constraint rejects row |
| T-CORE-003 | PRD 02 | Duplicate engagement/month pair | Unique constraint rejects row |
| T-CORE-004 | PRD 02 | Invalid month transition | Typed rejection and audit/transition evidence |
| T-CORE-005 | PRD 02 | Config effective August queried for July | July resolves previous version |
| T-AUTHZ-001 | PRD 14 | Inspect every exposed Java endpoint/service policy | JWT authentication plus organization/object permission is required; no anonymous business endpoint |
| T-AUTHZ-002 | PRD 14 | Direct API insert changes organization ID outside scope | Spring service rejects it and transaction persists no row |
| T-AUTHZ-003 | PRD 14 | Reporting/query across tenants | Controller/service scope filter and PostgreSQL integration test preserve tenant isolation |
| T-CORE-006 | PRD 02 | Publish overlapping configuration windows or point an engagement at another engagement's version | Rejected by service and PostgreSQL scope/effective-window constraints |
| T-CORE-007 | PRD 02 | Move a month through a permitted transition with an expected version | Effective configuration is snapshotted and append-only transition history records actor, reason and versions |
| T-CORE-008 | PRD 02 | Skip directly from planning to a terminal state or reuse a stale month version | Typed conflict; no state or history mutation |
| T-CONTACT-001 | PRD 03 | Add a verified approval contact from an active participant organization | Versioned group membership is created |
| T-CONTACT-002 | PRD 03 | Add a disabled, expired, inactive or foreign-organization contact | Rejected without disclosing cross-scope data |
| T-APPROVAL-001 | PRD 03 | Publish a policy whose N-of-M quorum exceeds eligible authorities | Rejected before publication |
| T-APPROVAL-002 | PRD 03 | Create the same approval request twice with one idempotency key | Same immutable request is returned; different content with that key conflicts |
| T-APPROVAL-003 | PRD 03 | Two distinct authorities approve a 2-of-3 stage | First action remains pending; second advances or completes the request |
| T-APPROVAL-004 | PRD 03 | One authority acts directly and a delegate tries to vote for the same authority | Duplicate authority vote conflicts and does not contribute twice to quorum |
| T-APPROVAL-005 | PRD 03 | Request creator acts under a self-approval-prohibited policy | Typed conflict and no action evidence |
| T-APPROVAL-006 | PRD 03 | Delegate acts with expired, revoked, over-broad or stage-disabled delegation | Denied; valid bounded delegation records actor and delegated-from authority |
| T-APPROVAL-007 | PRD 03 | Project-A role holder acts on Project-B request | Denied by exact project permission and database eligibility predicate |
| T-APPROVAL-008 | PRD 03 | Actor reuses a stale request version or acts on a non-current stage | Typed conflict/database rejection; immutable prior actions remain |
| T-APPROVAL-009 | PRD 03 | An `ALL` stage gains another eligible authority after publication but before request creation | Request snapshot derives quorum from all request-time authorities; the earlier smaller quorum cannot complete it |
| T-APPROVAL-010 | PRD 03 | A future-effective revision is drafted and published while the current version remains effective | New requests continue using the current published version until the non-overlapping handoff date |
| T-APPROVAL-011 | PRD 03 | An actor omits the reason when the captured policy requires evidence | Typed rejection, no action/version mutation; UI marks the field required |
| T-APPROVAL-009 | PRD 03 | Request creator delegates to another actor and that actor attempts approval under a self-approval-prohibited policy | Rejected because self-approval compares the requester with the original authority |
| T-APPROVAL-010 | PRD 03 | Reuse an approval-action idempotency key | Exact retry returns the same result; changed decision/delegation/comment conflicts |
| T-APPROVAL-011 | PRD 03 | Role, membership or contact-group eligibility changes after request creation | Pending request continues to use its immutable eligible-authority/quorum/delegation snapshot |
| T-APPROVAL-012 | PRD 03 | Create a revised policy and publish it | Stable policy identity gains a new draft version; publishing supersedes the previous published version without rewriting it |
| T-APPROVAL-013 | PRD 03 | Caller fabricates object type/version/hash/project or tries a non-REOPEN policy through the public request endpoint | Rejected; server accepts only policy/month/idempotency input and derives all governed evidence |
| T-APPROVAL-014 | PRD 03 | Final authority approves a bound reopen request | Request approval and `REOPEN_REQUESTED` → `REOPENED` month transition commit atomically with transition evidence |
| T-APPROVAL-015 | PRD 03 | Runtime SQL attempts to update/delete a request or reopen a month without a matching approved request | Database trigger/grant backstops reject the bypass |
| T-RBAC-001 | PRD 03 | Inspect canonical role inventory and business-approval mappings | All required templates exist; platform admin, support operator and service account have no implicit business-approval permission |
| T-OPENAPI-001 | PRD 22 | Inspect generated executable contract | Administration, request/action, transition, permission and error schemas/paths are present |

Every authorization case must run for allowed and denied actors against a Flyway-migrated Testcontainers PostgreSQL database and, before release, an approved staging PostgreSQL environment.
