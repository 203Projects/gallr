# Data model: Visit diary with 감상 joined in

Exact SQL shapes, RPC signatures and error codes are in [contracts/database.md](contracts/database.md); Kotlin
signatures in [contracts/shared-diary-api.md](contracts/shared-diary-api.md).

## Diary entry (one per person per exhibition)

The entry **is** the visit record, extended. On the device it is `ExhibitionVisit`; in the account it is a
`content_private.my_gallr_visits` row (primary key `(user_id, exhibition_id)`).

| Field | Device (`ExhibitionVisit`) | Server column | Rule |
|---|---|---|---|
| exhibition | `exhibitionId` | `exhibition_id` | unchanged |
| kept record | `snapshot` (names, venue, dates, cover) | `name_ko` … `cover_image_url` | unchanged; written at first record, never refreshed (spec 053 FR-014) |
| record time | `createdAt` | `visited_at` | unchanged meaning: when the record was made, not the visit date |
| visit date | `visitDate: LocalDate? = null` | `visit_date date null` | null = 날짜 미정; a sheet save requires a date; bulk and strip adds leave it null |
| feeling | `feeling: VisitFeeling? = null` | `feeling text null` check in (`loved`, `fine`, `not_for_me`) | private |
| tags | `tags: List<VisitTag> = emptyList()` | `tags text[] not null default '{}'` | ≤ 3, distinct, from the nine wire ids; private, never moderated |
| note | `note: String? = null` | `note text null` | 1–280 when typed; a sign-in merge may produce up to 562 (P5); blank is stored as null |
| entry updated | `entryUpdatedAt: Instant? = null` | `entry_updated_at timestamptz null` | set by the device on every sheet save; last write wins; null for 1.15 records |

**Validation (device and server agree):** `visitDate` within `[openingDate, min(closingDate, today)]` when chosen in
the sheet (`visitDateOptions`); the server only checks it is a date (the show's dates may change later, edge case 1).
Tags: at most three, no duplicates. Note: trimmed of trailing whitespace; empty → null.

**State transitions**

```text
(no entry) --sheet save / bulk add / strip add / first save of a legacy 감상--> entry
entry --sheet save (all fields replaced, newer entryUpdatedAt wins)--> entry
entry --기록 삭제 (delete_entry)--> (no entry) and its public note deleted
guest entry --sign-in (merge_visit: fill empty account fields; differing notes joined account-first)--> account entry
entry --note cleared--> entry (its public note withdrawn: update_visit deletes it)
```

## Feeling and tag vocabularies (fixed, shared)

| Feeling wire | KO | EN | Ranking |
|---|---|---|---|
| `loved` | 좋았어요 | Loved it | anchor at save weights |
| `fine` | 그저 그랬어요 | It was fine | today's visit weights |
| `not_for_me` | 아쉬웠어요 | Not for me | no anchor; half a 덜 보기 step on shared terms and artists |

| Tag wire | KO | EN | Mood term (only on a 좋았어요 visit) |
|---|---|---|---|
| `the_work` | 작품이 좋았어요 | The work | — |
| `the_space` | 공간이 좋았어요 | The space | — |
| `food_for_thought` | 생각할 거리가 많았어요 | Food for thought | — |
| `calm_to_linger` | 조용히 머물기 좋았어요 | Calm to linger | `mood:quiet-meditative` |
| `playful` | 즐거웠어요 | Playful | `mood:playful` |
| `moving` | 마음에 남았어요 | Moving | — (P8) |
| `overwhelming` | 압도적이었어요 | Overwhelming | `mood:monumental` |
| `heavy` | 마음이 무거웠어요 | Heavy | `mood:dark` |
| `see_again` | 다시 보고 싶어요 | Would see again | — |

Term ids verified: `20260831020202_explainable_art_metadata.sql:169-174`, `20261104120000_art_vocabulary_expansion.sql:32-35`.

## Public note (감상): `public.thoughts`, extended

| Column | Type | Rule |
|---|---|---|
| `id`, `user_id`, `exhibition_id`, `content`, `created_at`, `updated_at` | existing | `content` ≤ 280; unique `(user_id, exhibition_id)` |
| `is_approved` | boolean, now `not null default false` | kept for 1.15; always equals `moderation_status = 'approved'` (check constraint) |
| `moderation_status` | text not null default `'pending'` | `pending` / `approved` / `rejected` |
| `rejection_reason` | text null | required when `rejected`; one of `not_about_exhibition`, `promotional`, `inappropriate_language`, `personal_information`, `other` |
| `requested_at` | timestamptz not null default now() | when the current text was asked for review; the review queue's order and staleness token |
| `decided_at`, `decided_by` | timestamptz null, uuid null → `auth.users` on delete set null | last staff decision |
| `approved_at` | timestamptz null | when the current text was approved; identifies "the approved text" for reports |
| `approval_seen_at` | timestamptz null | set once by the author's app after showing 감상이 공개됐어요 |

**Link to the entry:** the same `(user_id, exhibition_id)`. A 감상 with no visit row (the four existing ones, any
posted from 1.15) appears in the diary as an undated entry (`buildDiary`); the first save of that entry creates the
visit row with the public text as its note.

**Author-visible status** (`PublicNoteStatus`, derived on the device): `PRIVATE` (entry has a note, no thoughts row),
`SENDING` (a publish or unpublish mutation is in the outbox, including while a session refresh is pending),
`RETRY` (that mutation failed and the session is not pending), `IN_REVIEW` (`pending`), `PUBLISHED` (`approved`),
`NOT_PUBLISHED` (`rejected`, with its reason).

**Transitions**

```text
(none) --publish_note (1.16) or table insert (1.15)--> pending
pending --staff approve--> approved (approved_at = now, approval_seen_at = null)
pending --staff reject + reason--> rejected
approved --author changes content (any version) or asks again--> pending (is_approved false at once)
rejected --author asks again (same or new text)--> pending
approved --staff take-down after reports + reason--> rejected
any --author unpublishes, deletes the entry, or clears the note--> (row deleted; private note kept unless the entry was deleted)
approved --author's app shows the one-time line--> approved with approval_seen_at
```

Authors can never move a row to `approved` or `rejected`; only `content_private` staff functions can, under the
publisher role.

## Report: `public.thought_reports` (new)

| Column | Type | Rule |
|---|---|---|
| `id` | uuid pk | |
| `thought_id` | uuid → `public.thoughts(id)` on delete cascade | |
| `account_id` | uuid → `auth.users(id)` on delete cascade | the reporter |
| `approved_at` | timestamptz not null | the reported text's `approved_at`; one report per account per approved text (unique) |
| `reason` | text | `inappropriate`, `promotional`, `not_about_exhibition`, `personal_information`, `other` |
| `created_at` | timestamptz default now() | 48-hour clock starts here |
| `resolved_at`, `resolution`, `resolved_by` | null until decided | `dismissed` (kept) / `upheld` (taken down); both null or both set |

Open = `resolved_at is null`. While open it hides the 감상 from its reporter only (`list_exhibition_thoughts`). Keep
resolves every open report of that 감상 as `dismissed`; take-down resolves them as `upheld` and rejects the 감상 with a
reason.

## Moderation decision (no new table)

Recorded on the row (`decided_at`, `decided_by`, `rejection_reason`) and in `content.audit_log`
(`entity_type = 'thought'`; actions `thought.approved`, `thought.rejected`, `thought.reports_dismissed`,
`thought.taken_down`; metadata: status before/after, reason, resolved report count — never the text).

## Device-only state

| Store | Key (private constant) | Shape | Cleared |
|---|---|---|---|
| Hidden authors | `HIDDEN_THOUGHT_AUTHORS_KEY` = `hidden_thought_authors_v1` | JSON list `{user_id, display_name, hidden_at}` | never on sign-out; reinstall clears |
| Ended-saved strip | `DIARY_ENDED_SAVED_DISMISSED_COUNT_KEY` = `diary_ended_saved_dismissed_count_v1` | int: the count when closed | never on sign-out |
| Guest entries | existing `my_gallr_exhibition_visits_v1` | `VisitArchivePayload` v1 with the new optional fields | cleared per entry after the server acknowledges the merge (existing) |
| Account outbox | existing `my_gallr_account_state_v1` | per-account state with new mutation kinds | existing rules |

## Derived view: the diary (`buildDiary`, never stored)

Inputs: entries (visits), own public notes, catalogue, saved ids, today, filter, dismissed strip count. Output: months
(newest visit date first, entries in a month newest first, ties by name), a final 날짜 미정 group (by record time,
newest first), undated rows for public notes without an entry, the ended-saved count (saved, `closingDate < today`, no
entry) and whether the strip shows (count > 0 and count > dismissed count). The 공개한 감상 filter keeps entries whose
note has any public status. Independent of the LIST tab's search, city and filters (SC-008).

## Server-only counts (owner, read-only)

SC-001/SC-002 read only whether diary fields are filled (`visit_date is not null and (feeling is not null or
cardinality(tags) > 0 or note is not null)`), never which feeling or tag; SC-003/SC-004 read `requested_at`,
`decided_at` and report times. Queries in [quickstart.md](quickstart.md).
