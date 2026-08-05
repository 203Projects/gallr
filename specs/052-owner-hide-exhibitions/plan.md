# Implementation Plan

1. Add failing pgTAP coverage for grants, authorization, optimistic identity,
   idempotence, list filtering, audit evidence, and canonical preservation.
2. Add an additive migration with owner-hidden metadata and a narrow RPC.
3. Add failing repository and component tests, then implement the repository
   method and confirmation UI.
4. Update the owner release runbook and verify replay, pgTAP, lint/advisors,
   gallery tests/typecheck/build, and desktop/mobile behavior.
