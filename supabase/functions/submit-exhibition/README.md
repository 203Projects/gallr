# Retired anonymous exhibition intake

`submit-exhibition` is retained as tested rollback history for the former
account-free gallery submission flow. It is not part of the current gallery
owner release: `gallrmap.com/submit/` links to `gallery.gallrmap.com`, and the
Eleventy build neither configures this endpoint nor ships the retired form
script.

Do not deploy or expose this function without an explicit rollback decision. The
current release sequence and smoke gates are documented in the
[gallery owner release runbook](../../../docs/gallery-owner-release-runbook.md).
The archived anonymous-intake architecture and its additional secret/origin
requirements are documented in the
[rollback workflow](../../../docs/gallery-submission-workflow.md).

The function remains in CI so rollback code cannot silently decay:

```bash
deno task test
deno task check
```

Its `verify_jwt = false` setting is intentional for the retired public intake.
If restored, the handler must retain exact-origin validation, multipart and
image limits, honeypot/rate-limit enforcement, server-side field allowlisting,
private media storage, and the staff-only review gate. A public submission must
never publish an exhibition directly.
