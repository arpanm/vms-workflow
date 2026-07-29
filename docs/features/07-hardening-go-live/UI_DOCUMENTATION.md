# F07 — UI Functional Flow and How-to

F07 hardens the shared application shell. Retention and feature-flag governance
are currently operator/API workflows documented through authenticated Swagger;
the browser does not invent a second client-side authority model.

## Shared safety flow

1. Sign in through the configured deployment entry point.
2. Use the sidebar to enter a permitted feature. On mobile, selecting a route
   closes the drawer so content receives the viewport.
3. Keyboard users can tab to **Skip to main content** and move directly to the
   page landmark.
4. If an unexpected error occurs, the shell announces a generic safe message,
   moves focus to the error heading and shows only a correlation-based support
   reference when one is available.
5. Retry the route or return to the dashboard. Send the support reference—not
   tokens, screenshots of restricted data or raw payloads—to support.

Demo persona controls remain presentation-only and never grant server
permission.

## Accessibility behavior

- Shell navigation and application controls have accessible names.
- Decorative icons are hidden from assistive technology.
- Utilization and approval coverage bars have contextual accessible names.
- Critical route errors use an assertive alert and stable focus target.
- At 390px attendance reflows without document-level horizontal overflow.
- Tablet finance and governance routes retain named controls.
- `prefers-reduced-motion: reduce` collapses animations/transitions while
  preserving navigation.
- The automated axe gate blocks serious/critical WCAG 2.1 A/AA findings in the
  covered routes.

Automated checks do not replace the required representative-user keyboard and
screen-reader review.

The final local browser-contract matrix passes 274/274 cases across the
configured Chromium, Firefox, WebKit, Android and iOS projects. Failure history
is preserved as 268/274 on the first full run, followed by an exact 7/7
failed-slice rerun and the 274/274 complete rerun. This does not close manual
representative-user accessibility, production identity or live-provider
acceptance.

## Retention operator flow

Use authenticated Swagger:

1. Create a versioned organization schedule. Use an approved policy reference;
   do not invent a statutory period.
2. Run a dry run at an explicit `asOf`. Review each candidate and its
   `ELIGIBLE`, `HELD` or `NOT_DUE` reason.
3. Resolve unexpected holds/state changes before execution.
4. Execute with a new idempotency key. Review candidate outcomes and the
   preserved SHA-256 expiry proofs.
5. If retries exhaust, investigate the correlated failure. A distinct,
   authorized operator records dead-letter recovery with a reason before a new
   bounded cycle.

The workflow expires temporary export/share capability. It does not silently
delete packages or closed evidence.

## Legal-hold flow

1. Place a hold on the exact authorized artifact with a reason code.
2. Confirm the `PLACED` transition and effective hold.
3. To release, submit a release request. When two-person release is configured,
   the hold stays effective.
4. A different active authorized actor records release approval.
5. Confirm the append-only transition history before resuming retention.

Do not use the legacy finance hold toggle to bypass this flow; the service and
database reject that release path.

## Feature-flag flow

1. A platform flag manager defines the dotted key, owner, safe default,
   description and reason.
2. An authorized manager appends a SYSTEM, ORGANIZATION or ENGAGEMENT version
   with an effective window and dependency list.
3. An authorized reader evaluates the flag for a real server-side scope.
4. Inspect `source`, `version`, `evaluatedAt` and `enabled`.

An enabled response always contains `authorizationGranted: false`. The target
operation must independently pass its normal permission and object-scope
checks.

## Support and operations

Use [F07 runbooks](../../operations/F07-RUNBOOKS.md) for access, attendance,
providers, migration, evidence, invoice, retention/privacy, backup/DR and
security incidents. Use
[release and DR procedure](../../operations/F07-RELEASE-AND-DR.md) for rollout.
