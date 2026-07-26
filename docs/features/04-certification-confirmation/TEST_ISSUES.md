# F04 Test Issues — Consolidated Ledger

There is no recorded unresolved local harness defect in the final root-verified 111/64/59 evidence. The following limits remain and must not be relabelled as passing acceptance tests:

| ID | Limit | Required resolution |
| --- | --- | --- |
| `F04-TEST-SYSTEM-001` | Playwright uses intercepted deterministic APIs. | Run a controlled browser/BFF/Java/PostgreSQL system lane. |
| `F04-TEST-PROVIDER-001` | Email/mailbox/storage callbacks and retention are not live-tested. | Execute approved sandbox/live provider acceptance. |
| `F04-TEST-DEPLOYMENT-001` | Testcontainers does not prove production DB identities/grants or edge controls. | Validate deployment security design and runtime configuration. |
| `F04-TEST-ROOT-001` | Current consolidated totals are reported by agents. | Root agent reruns the relevant quality commands and records the result. |
| `F04-TEST-V13-001` | V13 focused coverage passes, but a fresh Testcontainers schema does not prove remediation of pre-existing V11/V12 data, and the concurrent F05 test cannot prove an external consumer performed compensation. | Audit/migrate existing F04 data before upgrade; obtain deployed-consumer acceptance for the invalidation contract. |

Historical test-review findings and their resolutions remain cross-linked: [backend issues](TEST_ISSUES-BACKEND.md), [frontend issues](TEST_ISSUES-FRONTEND.md), [backend fixes](FIXES-BACKEND.md), and [frontend fixes](FIXES-FRONTEND.md).

The independent final review ran the focused V13 Failsafe command and observed
109 integration plus 2 unit tests passing, with no local harness failure. That
evidence does not close the system/provider/deployment limits above.
