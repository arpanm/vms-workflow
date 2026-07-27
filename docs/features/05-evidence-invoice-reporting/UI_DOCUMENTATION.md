# F05 — UI functional flow and usage

F05 is delivered through the Finance workspace. UI permissions only control
presentation; the Java API makes every authorization decision.

## Routes

| Route | Intended users | How to use it |
| --- | --- | --- |
| `/finance` | Scoped vendor, finance, governance and related personas | Select an authorized month, create the appropriate invoice record, upload a scanned document, inspect readiness, generate/view packages, submit an exact ready lineage and view permitted payment history. |
| `/finance/procurement` | Scoped Procurement/Finance actors | Use the nine-pillar control tower to drill into a month/invoice, record a version-bound review, create/answer/close a query, request/approve an exception where authorized, and append permitted payment status. Upstream evidence is read-only. |
| `/finance/reports` | Personas with reporting/export authority | Read metric definition/freshness, select authorized filters/current-vs-snapshot mode, request an export, observe queued/running/ready/dead-letter state and download only when the server authorizes it. |

## Main user flows

1. **Vendor evidence and invoice:** create primary/correction/credit/debit
   metadata, upload file plus classification/retention/source, wait for scan,
   inspect all readiness blockers and their source/version/CTA, then generate
   a package and submit the exact eligible package/readiness/invoice versions.
2. **Procurement decision:** view immutable manifest/history/diff, choose
   approve/change/hold/reject with reason, or request a bounded exception linked
   to the exact failed rule/readiness/package/policy versions. The requester
   never types or nominates an approver. If policy requires two people, the
   exception appears as `PENDING_SECOND_APPROVAL`; a different signed-in
   Procurement reviewer opens the same invoice, acknowledges the exact lineage
   and selects **Approve as current signed-in reviewer**. Self-approval, expiry,
   stale version and binding mismatch remain visible typed errors and do not
   activate the exception. A change creates a tracked correction query instead
   of editing upstream facts.
3. **Share/download:** create a recipient-specific `VIEW` or `DOWNLOAD` grant
   with future expiry/reason/confirmation; revoke with reason when needed.
   The UI never exposes a signed storage URL.
4. **Export:** select only available report definitions, preserve the returned
   cursor/filter state, request a job, retry through the permitted recovery
   path and download the checked result.

## State and accessibility behavior

All consequential dialogs show version, reason/acknowledgement and downstream
effect. The UI exposes loading, empty, stale, read-only/superseded,
scan-pending/quarantined, permission-denied, provider-not-configured,
version-conflict, job/dead-letter and expired-download states. Controls have
labels and text status in addition to color; tables provide captions and
responsive scroll/card behavior.

Pending exception cards disclose requester, invoice/package/policy versions,
expiry and the distinct-reviewer requirement. Accepted cards disclose the
server-recorded approving actor and timestamp; expired cards have no approval
control. UI permission checks are presentational only—the API authenticates the
current actor and enforces separation of duties.

Automated browser runtime and accessibility-depth evidence is pending; see
[TEST_ISSUES.md](TEST_ISSUES.md).
