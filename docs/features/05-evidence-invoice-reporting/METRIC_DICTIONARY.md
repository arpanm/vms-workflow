# F05 — Metric dictionary

Metrics are persona-scoped and carry source/freshness/policy/timezone labels.
`0`, unavailable, stale and not-applicable are distinct states. No metric may
derive or expose salary, CTC, rate, markup, margin, payroll or employee-level
invoice allocation.

| Metric | Definition | Source / caveat |
| --- | --- | --- |
| Readiness completion | Eligible current invoice-readiness runs ÷ scoped current runs | Exact F04/package/invoice sources; exceptions remain separately labelled. |
| Confirmation completion | Verified/disclosed eligible F04 confirmation state for scoped months | F04 handoff only; Procurement exception is not confirmation verification. |
| Package currency | Current non-invalidated package versions for scoped months | Shows superseded/invalidated separately. |
| Invoice queue aging | Current invoices by governed lifecycle state and represented/recorded age | Does not calculate invoice value. |
| Procurement queue | Scoped review/change/hold/exception/query status and due-date state | Requires role-specific projection/masking verification. |
| Payment status aging | Append-only permitted AP/ERP/manual status dates | Recorded status only; never funds movement. |
| Export freshness | Generated time, source freshness, report/version and snapshot/current mode | Output rows must use the authorized report projection. |

The report-specific formula/projection and export parity validation remains an
open item in [CODE_ISSUES.md](CODE_ISSUES.md).
