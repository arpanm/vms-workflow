# F04 Backend Codegen — Provider-Neutral Local Vertical

## Result

Implemented an additive Java 25 / Spring Boot / PostgreSQL `JdbcTemplate`
vertical under `/api/v1/certification`. The implementation consumes the
current F03 frozen plan/baseline and F02 attendance snapshot without modifying
either source. It creates F04-owned versioned business facts, deterministic
hashes, server-resolved authority snapshots, outbox work, confirmation actions,
readiness runs and reopen invalidations.

No F04 test source was added or edited in this codegen stage. The existing
F01–F03 suite remains green after V11.

## API contract

Every mutation requires:

- authenticated JWT subject;
- server-side active membership/role/permission resolution;
- `If-Match: "<numeric-version>"`;
- `Idempotency-Key`;
- the matching expected version in the request body.

Responses carry an `ETag`. Cross-scope or party/SOD denial returns safe `404`.
Version/idempotency/state failures return `409` Problem Details with `code` and,
when applicable, `currentVersion`.

Primary routes:

- `GET /api/v1/certification/months/{monthId}`
- `POST /api/v1/certification/months/{monthId}/submissions`
- `POST /api/v1/certification/submissions/{submissionId}/submit`
- `POST /api/v1/certification/submissions/{submissionId}/clarifications`
- `POST /api/v1/certification/submissions/{submissionId}/certifications`
- `POST /api/v1/certification/months/{monthId}/summaries`
- `GET /api/v1/certification/months/{monthId}/readiness`
- `POST /api/v1/certification/months/{monthId}/confirmation-requests`
- `GET /api/v1/certification/confirmation-requests/{requestId}`
- `POST /api/v1/certification/confirmation-requests/{requestId}/actions`
- `POST /api/v1/certification/months/{monthId}/reopen-requests`

The response records align with the frontend `MonthCertificationView`,
`ReadinessView` and `ConfirmationRequestView` contracts. Backend persistence
uses the more precise PRD enums and maps the frontend aliases at the HTTP
boundary:

- frontend `DEFERRED` maps to client/vendor-deferred outcome from captured cause;
- frontend `CLIENT_DEPENDENCY_DEFERRED` maps to
  `DEFERRED_CLIENT_DEPENDENCY`;
- frontend `VENDOR_DEPENDENCY_DEFERRED` maps to
  `DEFERRED_VENDOR_DEPENDENCY`;
- criterion `MET/PARTIALLY_MET/NOT_MET` maps to immutable certification
  `ACCEPTED/PARTIAL/REJECTED`.

## Implemented local behavior

### Submission and certification

- Creates additive draft submission versions. Autosave supersedes an earlier
  draft instead of updating child outcomes or criterion responses.
- Resolves only the effective current `FROZEN` F03 version and its baseline.
- Captures every outcome separately from later client certification.
- Computes month-end Linear attempt status from F03 local snapshot records;
  Linear never creates an acceptance decision.
- Validates baseline coverage, frozen criteria coverage, declaration,
  non-simple variance fields, carry-forward proposal and pending plan revision
  before submit.
- Locks and SHA-256 hashes a submitted version.
- Resolves vendor submission authority from the vendor party and client
  certification authority from the assigned product owner/project scope.
- Stores item and criterion decisions separately with action hash and authority
  snapshot.
- Requires observations, non-accepted detail, partial scope and aggregate
  override rationale as applicable.
- Creates immutable clarification question/response records. A vendor response
  supersedes the old review round and opens a new round, preserving every prior
  decision.
- Creates origin-to-next-month carry-forward lineage for partial acceptance;
  the next engagement month must already exist.
- Creates only an explicit monthly decision. Counts/percentages never infer
  `CERTIFIED`.
- Generates a stable canonical summary manifest/hash from the exact plan,
  baseline, submission, decisions, criterion results, evidence metadata,
  Linear statuses and carry-forward records.

### Confirmation

- Re-runs five-pillar readiness from persisted source facts:
  roster/allocation, attendance, plan/Linear, certification, and
  confirmation/F05 handoff.
- Stores idempotent readiness input manifests/runs/results without deleting
  earlier evidence.
- Requires all pre-confirmation pillars before request creation.
- Snapshots categorized recipients and active assigned confirmer identity,
  verified email hash, project, sequence, role reason and quorum.
- Supports `ANY_ONE`, `ALL`, `N_OF_M`, ordered and project-specific quorum
  evaluation.
- Stores request business state independently from message transport state.
- Issues 256-bit opaque tokens and persists only PBKDF2-HMAC-SHA256
  hash/salt/work factor/expiry/consumption metadata.
- Supports authenticated in-app action now. The secure-link action path also
  validates request version, actor eligibility, expiry, PBKDF2 hash and
  single-use consumption when a token is supplied.
- Requires comments for correction/rejection, stores an immutable action hash,
  evaluates quorum and queues the outcome communication.
- Correction/rejection creates a downstream invalidation; it does not edit
  attendance, plan, submission or certification facts.
- Transport state, delivery/read/receipt, silence, auto-reply and elapsed time
  have no code path that creates `CONFIRMED`.

### Provider-neutral boundaries

- Notification outbox rows contain immutable template version, categorized
  recipient snapshot, accessible HTML/plain text, source version, rendered-body
  hash, archive-manifest hash, correlation and idempotency keys.
- Transport attempt rows are separate and append-only.
- `CertificationEmailAdapter` returns `NOT_CONFIGURED` locally and performs no
  real send.
- `EvidenceArtifactAdapter` returns `ACTION_REQUIRED` locally and exposes no
  storage credentials or signed URLs.
- `F05CertificationReadinessPublisher` exposes
  `certification.confirmation.readiness.v1` and returns `NOT_CONFIGURED`
  locally. F04 creates no package, invoice or Procurement decision.
- Inbound-message schema accepts durable provider fingerprints and explicitly
  quarantines receipt, auto-reply, forwarded, unmatched and malformed classes.
- Manual evidence/review schema separates represented time from recorded time
  and enforces a distinct second reviewer at the database boundary.

### Reopen and close lineage

- Reopen request captures category, reason, impacted IDs,
  package/invoice-impact disclosure, recipient snapshot and risk statement.
- Reopen request appends targeted invalidations and transitions the month to
  `REOPEN_REQUESTED`.
- Closure/reopen/invalidations are modeled in V11; no historical confirmation
  or upstream F02/F03 fact is deleted.

## Database integrity

V11 adds:

- `certification_version` for month-level optimistic concurrency;
- granular F04 permissions and role mappings;
- partial uniqueness for current policy, submission, review round, summary,
  confirmation request and closure;
- cross-scope triggers for baseline/submission/item/criterion/certification
  lineage;
- guarded state transitions for submissions, review rounds and confirmation
  requests;
- immutable-content guards for policies, outcomes, criteria, artifacts,
  evidence links, certification decisions, summaries, action facts, notification
  content, inbound/manual review evidence, readiness facts, invalidations,
  idempotency keys and audit/security/domain events;
- single-consumption-only token mutation;
- categorized recipient and transport/business-state separation;
- provider message fingerprint and outbox idempotency uniqueness.

## Configuration

Server-only configuration keys:

- `VMS_CERTIFICATION_TOKEN_TTL` (safe local default `PT72H`)
- `VMS_CERTIFICATION_CONFIRMATION_DUE` (safe local default `PT120H`)
- `VMS_CERTIFICATION_TOKEN_WORK_FACTOR` (minimum/default `120000`)
- `VMS_CERTIFICATION_EMAIL_PROVIDER_STATUS`
- `VMS_CERTIFICATION_OBJECT_STORAGE_STATUS`
- `VMS_CERTIFICATION_F05_HANDOFF_STATUS`

Provider statuses accept only `NOT_CONFIGURED`, `ACTION_REQUIRED` or
`CONFIGURED`. Deemed submission, certification and confirmation approval are
persisted as disabled in the effective policy.

## Deliberately pending

The following are not represented as production-complete:

- real email provider, sender identity, callback signing, delivery worker,
  retry scheduler/dead-letter replay UI and sandbox/live send;
- dedicated controlled mailbox authorization, webhook/poller, raw-MIME
  retention and inbound/manual-review APIs;
- object upload, content sniffing, malware scanning, retention/legal-hold
  processor and signed download URLs;
- secure plaintext-token dispatch. The local implementation hashes and discards
  plaintext because no approved secret-safe provider handoff exists; in-app
  confirmation is the usable local action;
- production SSO/OTP/MFA/step-up policy and provider evidence;
- policy administration UI/API for reminder schedules, delegates,
  project-specific exception policy and recipient groups;
- reopen approval/denial endpoint and selective recertification task executor;
- actual month close execution, because package/invoice/Procurement completion
  is owned by F05. V11 supplies the immutable closure and reopen data contracts;
- background reminder/expiry/inbound/retention workers and metrics exporters;
- trusted historical migration caller API;
- F04-specific automated tests, which are a later SDLC stage by instruction.

These pending boundaries remain visible as `NOT_CONFIGURED` or
`ACTION_REQUIRED`; no fixture, timeout, silence, receipt or provider status is
converted into business approval.

## Verification

- `mvn -DskipTests compile` — passed on Java 25.
- `mvn verify` — passed.
- Flyway applied V1–V11 plus existing test fixtures to a clean Testcontainers
  PostgreSQL 18 database.
- Existing integration result: 49 tests, 0 failures, 0 errors, 0 skipped.
