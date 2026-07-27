# F05 — Code analysis consolidation

**Status:** Static analysis is informative, not a substitute for the pending
full regression.

## Architecture assessment

F05 correctly places authority, scope derivation, policy resolution, immutable
lineage, private bytes and job control on the Java/PostgreSQL side. The browser
uses typed DTOs and cannot create authority by rendering an enabled control.
The F04 adapter is a narrow consuming boundary; it rejects invalid handoffs and
F05 invalidation preserves historical lineage instead of rewriting upstream.

## Follow-up analysis items

1. Verify exception workflow reachability with a natural blocked state; the
   post-review guard correction is present but must be tested.
2. Verify that each export report uses its own authority-snapshot-bound
   projection, masking and metric definition rather than a generic row set.
3. Replace the current bounded, opaque in-memory cursor with scoped database
   keyset/snapshot semantics for very large or concurrently changing histories.
4. Add controlled legal-hold/scanner transition audits and their tests.

The original detailed analysis is retained in
[CODE_ANALYSIS-BACKEND.md](CODE_ANALYSIS-BACKEND.md). Findings fixed after that
pass are documented in [FIXES.md](FIXES.md), without altering historical text.
