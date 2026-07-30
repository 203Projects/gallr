# Feature Specification: Automatic Media Publishing

## User story

As a Gallr administrator, I can upload an exhibition image and have it become
publishable automatically, without finding a worker token or running a terminal
command.

## Acceptance criteria

1. A private server-side schedule invokes the existing outbox worker once per
   minute.
2. Each invocation preserves the existing one-media-event claim and lease
   boundary.
3. Non-media outbox events remain pending when no general delivery receiver is
   configured.
4. The worker credential is stored encrypted in Supabase Vault and is never
   included in a browser bundle, migration, cron command, log, or repository
   file.
5. The scheduled request targets only the configured project’s
   `outbox-worker` Edge Function and uses the worker’s existing opaque bearer
   token.
6. The schedule can be enabled and disabled independently in staging and
   production without redeploying the Admin application or worker.
7. Admin continues polling an attached image while it is processing and enables
   Publish automatically after every attached image reaches `published`.
8. While Publish is disabled only because an image is processing, Admin explains
   that processing is automatic and normally completes within about one minute.
9. Rejected images continue to show the existing remove-and-reupload
   instruction.
10. Scheduler invocations and HTTP responses remain inspectable through
    Supabase Cron and `pg_net` run history.

## Out of scope

- Processing more than one outbox event in a single worker invocation.
- Delivering non-media lifecycle outbox events.
- Changing image validation, transformation, or immutable public paths.
- Automatically publishing an exhibition after its images finish processing.
- Enabling the production schedule without a separate production authorization.
