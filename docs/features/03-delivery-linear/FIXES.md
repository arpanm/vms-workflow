# F03 Fix Disposition

**Date:** 2026-07-26

## Resolved local P0 findings

V10's focused review closes the local P0 integrity gate. Its migration and
tests protect terminal approval evidence, require the eligible checksum-matched
quorum before freeze, and prevent invalid frozen-version ownership/lineage
changes. The recorded full Maven/Testcontainers result is **49 tests passing**.

This disposition is limited to the reviewed provider-neutral local vertical.
It is not a claim of live Linear or mail readiness.

## Open local P1 findings

- No autonomous queue claimant, retry/backoff, dead-letter worker or
  replay-enqueue semantics; current processing is an authorized command.
- Webhook size/content checks are incomplete perimeter controls: allocation,
  compression, rate and concurrency limits still need implementation and tests.
- Completeness exceptions, controlled recipient groups and no-deliverables
  approval are not yet authoritative.
- Revision lacks a complete editable field/add/remove diff and effective
  baseline comparison; checksum revision/plan/month context remains incomplete.
- Scheduled delta/nightly/month-end reconciliation and full fake-provider/mail
  failure coverage are absent.
- Some lifecycle/dependency/queue constraints and exhaustive quorum,
  authorization and least-privilege coverage remain incomplete.

## External blockers

Reliance must approve/configure the Linear OAuth app, workspace/team scopes,
app actor, webhook endpoint/secret and live GraphQL access. A mail provider,
sender/mailbox, retention/inbound policy and approved contact groups are also
required. Real BFF-to-Java-to-PostgreSQL browser acceptance remains blocked by
the identity and controlled-environment gates.

See [CODE_ISSUES.md](CODE_ISSUES.md), [TEST_ISSUES.md](TEST_ISSUES.md) and
[POST_FIX_REVIEW.md](POST_FIX_REVIEW.md) for the preserved review history.
