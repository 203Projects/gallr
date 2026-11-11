# Contract: Admin 감상 section (admin/)

Staff tool (constitution VII does not apply). Follows `RouteModerationWorkspace` and its repository pattern
(`admin/src/components/RouteModerationWorkspace.tsx`, `admin/src/repositories/AdminRouteRepository.ts:66-82`,
`SupabaseAdminRouteRepository.ts:25-107`). Deploys after M2 and before M3 (`admin/AGENTS.md` gets the note).

## Section, routing and gating

- `AdminSection` gains `"Thoughts"` (`admin/src/domain.ts:108-116`); slug `thoughts` → `?section=thoughts`
  (`:879-899`). Labels: `navigation.thoughts` = `Visitor notes` / `감상` (`admin/src/i18n.tsx` beside
  `navigation.routes`). "Notes" is already the editor's-notes section.
- Visible to `publisher` and `admin`, hidden from `contributor` (`PrimaryNavigation.tsx:48-54`, as Notes is); the
  initial-section and `handleNavigation` guards in `App.tsx` (`:239-248`, `:1421-1431`) send a contributor to
  Exhibitions. Hidden navigation is UX only: the RPCs refuse a contributor (`thought_not_staff`).

## Repository

```ts
export type ThoughtRejectionReason = "not_about_exhibition" | "promotional" | "inappropriate_language"
  | "personal_information" | "other";
export type ThoughtReportReason = "inappropriate" | "promotional" | "not_about_exhibition"
  | "personal_information" | "other";
export type ThoughtReportResolution = "dismissed" | "upheld";

export type PendingThought = { id: string; content: string; exhibitionId: string; exhibitionNameKo: string | null;
  exhibitionNameEn: string | null; authorDisplayName: string; requestedAt: string; createdAt: string;
  wasApprovedBefore: boolean };
export type ReportedThought = { id: string; content: string; exhibitionId: string; exhibitionNameKo: string | null;
  exhibitionNameEn: string | null; authorDisplayName: string; approvedAt: string; openCount: number;
  firstReportedAt: string; reasons: Partial<Record<ThoughtReportReason, number>> };

export interface AdminThoughtRepository {
  listPending(): Promise<PendingThought[]>;                 // list_thought_review_queue
  decide(id: string, decision: { kind: "approve" } | { kind: "reject"; reason: ThoughtRejectionReason },
         expectedRequestedAt: string): Promise<void>;       // decide_thought
  listReported(): Promise<ReportedThought[]>;               // list_reported_thoughts
  resolveReports(id: string, resolution: { kind: "dismissed" } | { kind: "upheld"; reason: ThoughtRejectionReason },
                 expectedApprovedAt: string): Promise<void>; // resolve_thought_reports
}
```

Errors mapped at the adapter: `ThoughtModerationNotStaffError` (`thought_not_staff`), `ThoughtNotFoundError`,
`ThoughtReviewStaleError` (`thought_review_stale`, `thought_reports_stale`), `ThoughtInvalidTransitionError`,
`MalformedAdminThoughtPayloadError` (unknown or missing keys; any `feeling`, `tags`, `visit_date` or `note` key in a
payload is treated as malformed, so a server regression can never render private fields). An
`InMemoryAdminThoughtRepository` serves tests and fixture mode only.

## Workspace (`ThoughtModerationWorkspace.tsx`)

- Tabs `검토 대기 (n)` / `Pending (n)` and `신고됨 (n)` / `Reported (n)`; both lists oldest first (pending by
  `requestedAt`, reported by `firstReportedAt`).
- Each item: the text in full, author display name, exhibition name (or `전시 정보 없음` / `Exhibition unavailable`),
  `요청 <time>` / `Asked <time>`; reported items add `신고 n건` / `n reports`, the reasons with counts, `첫 신고 <time>` /
  `First report <time>` and `<h>시간 남음` / `<h>h left` until 48 hours, then `48시간 경과` / `Over 48 hours`.
  Re-review items carry `수정 후 재요청` / `Edited, asked again`.
- Pending actions: `승인` / `Approve`; `거절` / `Reject` → dialog with the five reasons as radio rows, one required,
  `거절하기` / `Reject`.
- Reported actions: `유지` / `Keep` (closes the reports); `내리기` / `Take down` → the same reason dialog, `내리기` /
  `Take down`.
- Never shows feeling, tags, visit date or any unpublished note text (US5 scenario 10).
- Stale result: `작성자가 내용을 바꿨어요. 목록을 새로 고쳤어요.` / `The author changed the text. The list was
  refreshed.`; not staff: `감상을 검토할 권한이 없어요.` / `You don't have permission to review notes.`; load failure:
  `감상 목록을 불러오지 못했어요.` / `Couldn't load notes.` with `다시 시도` / `Retry`.
- Learns from TODOS.md (route review, `:205-215`): the selected item's preview sits beside the list, not under it, and
  the destructive action is visually subordinate.

Messages live in `staff-i18n-messages.ts` under `thoughts.*` (EN and KO; a missing KO key is a type error).

## Tests (Vitest, written first)

`SupabaseAdminThoughtRepository.test.ts` (RPC names and argument keys, every error mapping, malformed payloads
including a private-field key), `InMemoryAdminThoughtRepository.test.ts`, `ThoughtModerationWorkspace.test.tsx`
(ordering, reason required for reject and take-down, stale refresh, KO copy, no private fields in the DOM),
`domain.test.ts` (slug), `PrimaryNavigation.test.tsx` (contributor hidden), `App.test.tsx` (deep link and
contributor redirect). Gate: `npm run typecheck && npm test && npm run build` in `admin/` with Node 22.23.1.
