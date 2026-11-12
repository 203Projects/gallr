# Quickstart: Visit diary with 감상 joined in

How to verify each slice and user story. In a worktree prefix Gradle with `ANDROID_HOME=$HOME/Library/Android/sdk`;
run Node tools with the root `.node-version` (`npx -p node@22.23.1 …` when the host Node differs).

## Database (slices 1–3)

```bash
node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs
node --test scripts/staging-rehearsal/lib/validate-migration-lineage.test.mjs
# clean replay + pgTAP in an isolated scratch Supabase workdir (see database-tests.yml); new suites 076–079
supabase test db
```

Staging, after the owner applies M1 → M2 → (Admin Preview) → M3 → M4 to `gallr-staging`:

1. A 1.15 build: post a 감상 → it is not public (anon read empty) and the author sees 검토 중. Approve it in Admin →
   public. Edit it through the table as the author (SQL with the author's JWT claims) → it leaves the public read.
2. A 1.15 build: record a visit, follow a gallery, open MY → nothing changed.
3. Admin Preview (staging): `?section=thoughts` as publisher → the 감상 above under 검토 대기; as contributor → hidden.

## Shared and app (slices 4–9)

```bash
./gradlew shared:allTests --tests '*Diary*' --tests '*EntryMerge*' --tests '*VisitEligibility*' \
  --tests '*PublicNote*' --tests '*ThoughtVisibility*' --tests '*VisitFeeling*' --tests '*TasteProfileRanking*'
./gradlew shared:ktlintCheck shared:allTests
./gradlew composeApp:ktlintCheck composeApp:allTests
./gradlew androidApp:ktlintCheck composeApp:ktlintCheck composeApp:testAndroidHostTest androidApp:lintDebug androidApp:assembleDebug
./gradlew composeApp:iosSimulatorArm64Test
```

Manual pass on Android and iOS, Korean and English, against staging:

**US1 (record).** Guest, running show: 다녀왔어요 → 오늘 preselected, 어제 offered only inside the run → 어제, 좋았어요,
save → row `11월 10일 다녀옴 · 좋았어요 수정`; MY → 일기 lists it under this month; force-quit and reopen → kept. Closed
show: no preselected date, no 오늘, 저장 disabled until a date. Upcoming show: no row and no 감상 남기기. Airplane mode,
signed in: save → shows at once; reconnect → a second device signed in to the same account shows the entry with its
feeling and tags; reinstall and sign in → all entries return.

**US2 (diary).** Entries in two months → two month headings, newest first; a bulk-added show under 날짜 미정; type a
LIST search and return → unchanged; save two closed shows → strip reads 2 → add them → both under 날짜 미정; close the
strip → gone until a third saved show ends; Profile has no 전시 일기 grid and its 감상 count opens 일기 with 공개한 감상.

**US3 (publish).** Signed in, write a note, choose 전시 페이지에 공개 under 누가 볼 수 있나요?, save → 검토 중; the exhibition page does not show it.
Approve in Admin → page shows it with your name; reopening the diary shows 감상이 공개됐어요 once. Edit the note → page
keeps the old text; 수정한 내용으로 다시 요청 → page shows nothing for it, status 검토 중. Reject with 광고·홍보 → 공개 안
됨 · 사유: 광고·홍보, note intact. Choose 나만 보기 → 비공개 and gone from the page. Delete the entry → the confirmation names the
published note; both gone. Guest → `로그인하면 전시 페이지에 공개할 수 있어요` opens sign-in and returns to the sheet.

**US4 (report and hide).** Reader A reports a 감상 with 광고·홍보 → `신고했어요`, it disappears for A; reader B still
sees it. Staff keep it → A sees it again with 신고함. Hide its author → all their 감상 vanish on this device, 되돌리기
restores, Settings → 숨긴 사용자 lists them with 숨기기 해제. A guest choosing 신고 → sign-in, then the sheet.

**US5 (Admin).** Pending list oldest first; reject requires a reason; reported list shows first-report time and hours
left; take down → gone for everyone, author sees 공개 안 됨 with the reason; a 1.15 post appears under 검토 대기; no
feeling, tag or visit date anywhere on the page; a 1.16 Profile shows no admin queue.

**US6 (recommender).** Covered by `VisitFeelingRankingTest` and `TasteProfileRankingTest`; on device: mark a visit
아쉬웠어요 → a show sharing its artist drops in 내 취향 but stays in the personal group.

## Admin (slice 2) and web (slice 10)

```bash
(cd admin && npm ci && npm run typecheck && npm test && npm run build)
(cd web && npm ci && npm test)
git diff --check
```

## Server counts (owner, read-only, production)

```sql
-- SC-001 / SC-002: filled-ness only, never which feeling or tag
select count(distinct user_id) as accounts_with_entries,
       count(*) filter (where entry_updated_at is not null) as entries_saved_on_116,
       count(*) filter (where entry_updated_at is not null and visit_date is not null
                        and (feeling is not null or cardinality(tags) > 0 or note is not null)) as complete_116
from content_private.my_gallr_visits;
-- SC-003: pending count and oldest wait
select count(*), max(now() - requested_at) from public.thoughts where moderation_status = 'pending';
-- SC-004: reports open longer than 48 hours (expected 0) and decided late
select count(*) filter (where resolved_at is null and created_at < now() - interval '48 hours') as overdue_open,
       count(*) filter (where resolved_at - created_at > interval '48 hours') as decided_late
from public.thought_reports;
-- SC-011: the four 감상 public on 2026-11-11 stay approved
select count(*) from public.thoughts where moderation_status = 'approved' and created_at < '2026-11-11';
```

Run with `supabase db query --linked --project-ref <ref> -f query.sql < /dev/null`.
