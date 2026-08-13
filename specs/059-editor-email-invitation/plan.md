# Implementation Plan: Editor email invitation

1. Add a protected pending-invitation table plus Admin registration and editor
   completion RPCs, retaining staff precedence and least privilege.
2. Reduce the Edge Function and Admin repository contract to email only and map
   provider failures to stable safe codes.
3. Add `editor_onboarding` access resolution and a dedicated self-profile form
   on the editor portal.
4. Keep new profiles unpublished and reuse existing Admin profile management
   for visibility and scheduling.
5. Verify React, Edge Function, migration lineage, pgTAP, database lint, and
   security advisors before promotion.
