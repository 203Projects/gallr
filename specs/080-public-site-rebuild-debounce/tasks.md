# Tasks: Durable public-site rebuild coalescing

- [x] T001 Add failing pgTAP coverage for delayed enqueue, burst extension,
  processing follow-up, non-lifecycle filtering, concurrency, and privileges.
- [x] T002 Add failing `outbox-delivery` tests proving lifecycle events stop
  calling Vercel directly and one durable rebuild event owns the hook/retry.
- [x] T003 Create the chronological migration with the private trigger/helper,
  fixed quiet window, bounded payload, advisory lock, and least privilege.
- [x] T004 Update `outbox-delivery` routing and operational documentation.
- [x] T005 Run lineage, focused and full database/Edge/web verification; record
  any environment-only gate separately without weakening it.
- [x] T006 Remove the completed rebuild-debounce entry from `TODOS.md`, update
  `CHANGELOG.md`, and open a reviewed `develop` PR.
