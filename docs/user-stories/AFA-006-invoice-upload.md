# AFA-006 — Upload an invoice

**Persona:** ArrowFoundry administrator/finance user
**Implementation:** IMPLEMENTED — F05 private invoice artifact flow
**Testing:** VERIFIED — finance backend/browser/system suites

## Description and UI flow

Open **Finance workspace**, create the invoice and upload its governed private
document using the effective classification/retention policy.

## Acceptance criteria

- Authorization, size/type, scan and hash checks fail closed.
- Replacements create new lineage; prior metadata remains auditable.
- Private bytes are never exposed by public URLs.

## Test cases

- Upload and replace a clean document.
- Quarantine malware and deny cross-tenant download.
