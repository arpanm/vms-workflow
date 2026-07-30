# CLI-001 — Create next-month tasks

**Persona:** Client product owner
**Implementation:** IMPLEMENTED — V46 work-item API and `/work-items` form
**Testing:** AUTOMATED — integration and Playwright; [execution](TEST_EXECUTION.md)

## Description and UI flow

Select the client engagement and next month, open **Client work items**, choose
**Add task**, and supply code, title, description, workflow and acceptance
criteria.

## Acceptance criteria

- Only `workitem.create` can create; project/month must belong to engagement.
- Task codes are unique per engagement and creation is audited.
- Next-month tasks appear in `NEXT` and the selected month view.

## Test cases

- Create a complete next-month task and retrieve it through `NEXT`.
- Reject foreign month/project and duplicate code.
