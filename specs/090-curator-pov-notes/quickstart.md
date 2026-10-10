# Quickstart: verifying curator POV notes

Run from the repository root unless noted. Credentials come from 1Password only; never paste a key
into a file in the repository.

## 1. Database

```bash
node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs
```

Then the pgTAP suite `supabase/tests/database/057_curator_notes.test.sql` in an isolated scratch
Supabase workdir as `docs/database-migration-lineage.md` describes. Expect every case in
`contracts/database-functions.md` to pass, including the denials for anonymous, signed-in and
contributor-tier callers and the stale-revision refusals.

## 2. Edge Function

```bash
cd supabase/functions/draft-curator-notes && deno task test && deno task check
```

Expect the handler, validation and hash tests to pass without network access. For a live check on
staging: set `ANTHROPIC_API_KEY` and `CURATOR_NOTES_SCHEDULE_TOKEN` as function secrets from the
1Password item, deploy to the staging project, then call `single` mode from Admin for one
exhibition and confirm a `draft` row with `model`, `prompt_version` and `source_hash` filled and a
single log line per exhibition.

## 3. Shared logic and app

```bash
./gradlew shared:ktlintCheck shared:allTests
./gradlew composeApp:ktlintCheck composeApp:allTests
./gradlew androidApp:ktlintCheck composeApp:testAndroidHostTest androidApp:lintDebug androidApp:assembleDebug
```

Expect: `CuratorEyeCardsTest` (catalogue membership, ended exhibitions dropped, newest first, limit,
language fallback), `CuratorNoteDtoTest` (malformed row skipped), `CuratorNoteRepositoryImplTest`
(failure boundary), `HomeViewModelTest` (notes failure yields an empty section) and
`CuratorNoteViewModelTest` (three states) pass.

Emulator walkthrough (`Medium_Phone_API_37.0`): with staging data holding three approved notes and
one draft, open the home tab and confirm three cards, the counter, the peeking next card and the
editor's name; tap one and confirm the detail screen opens scrolled to the block with the
disclosure line; switch to English in Settings and confirm the cards and block switch, with Korean
shown where English is empty; set font scale 2.0 and confirm the card text wraps without clipping.
Capture with `adb exec-out screencap -p`.

## 4. Admin

```bash
cd admin && npm run typecheck && npm test && npm run build
```

Expect `AdminCuratorNoteRepository.test.ts` (RPC mapping, stale and unsupported errors, denial,
malformed responses), `CuratorNotesWorkspace.test.tsx` (queue, edit, mark unsupported blocks
approve, decline with reason, regenerate), `carousel.test.ts` and `carouselZip.test.ts` to pass.

Manual: on a staging session, approve one edited draft and confirm it appears in
`curator_notes_published`; export it in both languages and confirm the archive contents, the
1080×1350 size, readable text, a scannable code on the closing slide and the hashtags; time the
export (under 30 s).

## 5. Analytics

```bash
cd supabase/functions/mobile-analytics && deno task test
```

Expect `curator_note` accepted as a discovery kind for `exhibition_impression` and
`exhibition_opened`.

## 6. Documentation

`git diff --check`; confirm `DESIGN.md` carries the new section pattern, the export type sizes and
the Decisions Log row; `CHANGELOG.md` under Unreleased; this spec folder's `tasks.md` once
`/speckit.tasks` has run.
