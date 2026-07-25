# F00 UI Documentation

## Historical legacy UI

The baseline tag provided `/`, `/engagements`, `/requirements`, `/scope`, `/approvals`, `/uat`, and `/invoices` under a shared sidebar. The role dropdown was a local demo display control and must not be described as sign-in or authorization. Its Supabase/Lovable dependencies are removed from the current working tree.

## Transition flow

1. An unauthenticated visitor reaches the replacement UI and is directed to OIDC sign-in.
2. The backend validates the JWT and resolves organization/engagement scope.
3. Navigation and actions are rendered from permitted scope; the server independently enforces every request.
4. Legacy fixed-cost routes may remain separately visible only during the approved compatibility window.

No replacement UI/auth flow has been implemented at F00. F01 owns the first usable authenticated shell and must document empty, loading, error, forbidden, and expired-session states.
