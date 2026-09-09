# Specification: Durable public-site rebuild coalescing

## Product boundary

Gallr must rebuild the public Eleventy catalogue after an exhibition is
published, archived, or restored, but a burst of editorial changes should not
create one Vercel build per lifecycle event. Coalescing must remain durable,
retryable, and safe when another edit arrives while a rebuild is already being
delivered.

## User Story 1 — Coalesce an editorial burst (P1)

As an editor, I can publish or archive several exhibitions in one work session
without causing a matching burst of redundant public-site builds.

### Acceptance criteria

1. Every exhibition publish, archive, or restore event durably requests a
   public-site rebuild in the same database transaction.
2. When no rebuild is processing, all lifecycle events inside a 30-second quiet
   window share one delayed `public_site.rebuild_requested` outbox event.
3. Each additional event moves the shared request's availability to 30 seconds
   after the newest committed event.
4. Lifecycle events retain their existing independent delivery for gallery
   alerts and other side effects. The database marks events whose durable
   rebuild was queued; unmarked events retain the direct Vercel hook as a safe
   mixed-version rollout fallback.

## User Story 2 — Never lose a late edit (P1)

As an operator, I can trust that an edit committed while a rebuild request is
processing causes a later rebuild rather than being incorrectly absorbed by an
already-started build.

### Acceptance criteria

1. A lifecycle event committed while a rebuild request is `processing` creates
   or extends a separate pending rebuild request.
2. Concurrent lifecycle inserts create at most one pending/failed rebuild
   request by using a transaction-scoped database lock.
3. A failed Vercel hook leaves the rebuild request retryable through the
   existing outbox backoff and dead-letter contract.
4. Unknown events still fail closed; known non-rebuild events remain
   acknowledged without a hook.

## Security and operations

- The database trigger and helper are private, use an empty `search_path`, and
  grant no callable surface to `PUBLIC`, `anon`, `authenticated`, or
  `service_role`.
- `VERCEL_DEPLOY_HOOK_URL` remains server-only and is invoked only for the new
  rebuild event.
- No new credential, scheduler, remote service, or production configuration is
  introduced.
- Deployment remains inert until the migration and updated `outbox-delivery`
  function are rolled out together through staging.

## Success criteria

- pgTAP proves initial enqueue, burst extension, concurrent-safe single pending
  request, processing follow-up, event filtering, and least privilege.
- Edge tests prove lifecycle events no longer call Vercel directly, the durable
  rebuild event calls it exactly once, and hook failures remain retryable.
- Existing gallery-alert, owner-email, mirror, outbox, lineage, and product
  surface gates remain green.
