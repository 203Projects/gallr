# Research

- Decision: retain a full-screen layer within detail and its existing navigation. Rationale: avoids Compose Dialog presentation crash history and preserves detail state.
- Decision: render PNG once, decode through existing decodeImageBitmap adapter, share the same card. State holder takes injected rendering/sharing functions and coroutine scope for deterministic cancellation tests.
- Decision: retain native completion guard until sheet dismissal (Android activity result; iOS completion plus activity-controller disappearance); no time-based debounce. Native testing reproduced outside-tap cancellation bypassing the iOS completion handler, so disappearance is covered by an idempotent completion path.
- Decision: use UIActivityItemSource and LinkPresentation if simulator compilation succeeds; otherwise descriptor-named PNG URL per requested fallback.
- Upstream reconciliation (2026-09-22): develop already supplies transactional intake/decision payloads, bilingual templates and query-parameter Admin review links. Reuse these contracts; do not introduce duplicate event families or obsolete hash routes.
- Decision: capture the claim email before later Auth changes. Backfill from the most recent saved claim-intake address where available, falling back to current Auth only for older claims without intake evidence. Reapplying the migration must not refresh an existing snapshot.
- Decision: add optional submission contact input/RPC overload; retain the original four-argument RPC and upstream owner withdrawal/draft guards.
- Decision: extend existing outbox delivery, stable keys and retries; explicit environment/sink configuration. Production sender may use verified auth.gallrmap.com subdomain, per recorded runbook incident.

No unknown architecture choice blocks local implementation. Native destination behavior and real email delivery require later platform/staging verification.
