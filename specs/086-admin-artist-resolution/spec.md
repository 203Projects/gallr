# Admin artist resolution

Source: the 2026-09-28 P1 request for Resolve feedback. Production observations in
the request are historical evidence, not a fresh database inventory.

## Stories and acceptance

1. Staff can see and complete a resolution: clicking Resolve marks the selected
   credit RESOLVING, shows a bilingual prompt beside Cancel, prefills and focuses
   canonical search, and prefills both creation names. Results say Link to.
   Empty results explain creation. A prominent Create {name} action works once
   both names exist; missing translations are completed by staff, never invented.
2. Resolution preserves ordered credits: linking or creating replaces the
   suggestion, avoids duplicate canonical credits, clears mode and search, and
   retains unrelated terms. Reordering/removing rows keeps the selected target
   accurate. Cancel clears search and restores the previous creation fields.
3. Staff can prepare a reviewed canonical directory backfill from current
   published credits. An offline job produces a deduplicated review manifest and
   transactional SQL only for explicitly approved bilingual names. Ambiguous
   collective/multi-person credits require explicit review. Existing exact pairs
   are skipped and conflicting/archived identities block execution. Published
   versions are never rewritten. Remote execution is a separate rollout step.

## Scope

Admin component, translations, monochrome CSS, regression tests, and a
network-free scripts job/runbook. Gallery has no resolution control. No new RPC,
schema change, automatic identity inference, deployment, or production mutation.
