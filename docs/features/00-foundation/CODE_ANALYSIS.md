# F00 Code Analysis

The prototype dependency flow is `route → data hook → browser Supabase client → legacy table`; `requirements` and `approvals` include direct mutation paths. There is no backend bounded-context layer, no durable job runner, no API contract, and no immutable evidence implementation. The Java/PostgreSQL architecture is therefore a replacement foundation, not a framework swap within existing route files.

No destructive database action is approved. The legacy migration/table semantics are a source mapping problem; `requirements`, UAT, and invoice rows cannot be automatically recast as canonical deliverables, certifications, or evidence packages.
