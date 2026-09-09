# ADR-0006: Retain legacy Supabase key compatibility

**Status:** Accepted
**Date:** 2026-08-29
**Decider:** gallr owner/operator

## Context

Gallr's legacy application remains active and supported. Released clients and
compatibility infrastructure still depend on legacy Supabase key formats and
configuration names. Removing those readers or disabling the corresponding
project keys while the legacy app is active could break installed clients that
cannot be updated in place.

Newer Gallr surfaces already prefer publishable keys for public clients and
component-scoped secret keys for server functions. That preference improves new
deployments but does not remove the compatibility obligation to the active
legacy application.

## Decision

Legacy Supabase key retirement is excluded from the active roadmap while the
legacy app remains active. Keep the required legacy key formats, configuration
fallbacks, and regression coverage in place. Do not disable, delete, rotate, or
remove them merely as repository cleanup.

New components must still use the narrowest current credential type: public
clients receive only publishable/anonymous credentials, server components use
component-scoped secrets where supported, production and staging remain
separate, and 1Password remains the source of truth. Compatibility is not
permission to expose a service credential to a client.

Retirement may be reconsidered only after the owner/operator explicitly ends
legacy-app support and there is evidence that no supported client or integration
still depends on the legacy path. That future decision requires a new roadmap
item or superseding ADR plus separate authorization for each external project
change.

## Options considered

### A. Retire legacy keys now

**Pros:** Removes compatibility code sooner and aligns every consumer with the
new Supabase key model.

**Cons:** Can break an active installed application and its supporting
integrations without a reliable client-side migration mechanism.

### B. Retain compatibility while modern surfaces use preferred keys (selected)

**Pros:** Preserves the active legacy app while keeping new deployments on the
narrower key model.

**Cons:** Maintains fallback code and operational complexity for longer.

### C. Stop adopting newer key types anywhere

**Pros:** Minimizes short-term configuration variants.

**Cons:** Expands legacy dependence and prevents newer server components from
using better-scoped credentials.

## Consequences

- The legacy application remains functional and supported.
- Legacy key readers and tests are not technical debt scheduled for removal.
- New code must not expand legacy-key use when a preferred key type works.
- No Supabase key is changed by this decision record.
- A future retirement requires explicit support, evidence, rollback, and
  environment-authorization gates.

## Action items

1. Keep compatibility tests and current fallback readers intact.
2. Continue using publishable/component-scoped keys for new surfaces.
3. Revisit only after an explicit decision to end legacy-app support.
