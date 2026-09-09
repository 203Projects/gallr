# Plan: Admin list reconciliation

- Add failing component regressions for a save/list race and failed-filter stale rows.
- Track only server-confirmed records saved during the workspace session.
- Reconcile those records by numeric revision through the existing filter/sort merge helper.
- Clear list rows on the current request's failure.

Constitution check: this is a bounded Admin state fix with no schema, RPC, or UI
redesign. Tests precede implementation; the existing repository boundary and
filter semantics remain unchanged.
