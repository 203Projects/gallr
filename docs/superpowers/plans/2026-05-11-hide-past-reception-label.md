# Hide Past Reception Date Label Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the exhibition detail page hide the reception label (and its inline opening time) the calendar day after the reception date passes.

**Architecture:** Display-layer only. One branch in `receptionDateLabel()` flips from "format past date" to `null`. The single call site already conditions rendering on `null`, so the inline `openingTime` hides automatically. Five existing tests assert the old past-date behavior and must be updated; new tests cover the boundary cases from the spec's acceptance criteria.

**Tech Stack:** Kotlin 2.1.20 (KMP commonMain + commonTest), kotlinx-datetime, kotlin-test. Gradle task `:shared:testDebugUnitTest` runs the JVM unit tests; `:shared:allTests` runs all platforms.

**Spec:** `specs/039-hide-past-reception-label/spec.md`
**Branch:** `039-hide-past-reception-label` (already created off `develop`, spec already committed)

---

## File Structure

- **Modify:** `shared/src/commonMain/kotlin/com/gallr/shared/data/model/ReceptionLabel.kt` — replace the "past but exhibition still running" branch's body with `null`.
- **Modify:** `shared/src/commonTest/kotlin/com/gallr/shared/data/model/ReceptionDateLabelTest.kt` — update 5 existing tests that assert past-date label strings; add 3 new tests for the spec's acceptance criteria.
- **Untouched:** `composeApp/src/commonMain/kotlin/com/gallr/app/ui/detail/ExhibitionDetailScreen.kt` — the call site at lines 175-181 already handles `null` correctly.

---

## Task 1: Update existing tests to assert new hide-past behavior (TDD red)

The current test file has five tests that assert past-date labels — those become the spec's contract violation once the implementation changes. Update them first so the suite encodes the new contract. They will FAIL against the current implementation, which is exactly what we want before fixing the code.

**Files:**
- Modify: `shared/src/commonTest/kotlin/com/gallr/shared/data/model/ReceptionDateLabelTest.kt`

- [ ] **Step 1: Open the test file and update the five past-date tests in place**

Replace each of the following tests verbatim. Keep the test method names so any test reports / CI history stays linkable, but flip the assertion from `assertEquals(...)` to `assertNull(label)` and update the test body comment.

Replace lines 61-65 (`pastDateWithTimeEnglish`):

```kotlin
    @Test
    fun pastDateWithTimeEnglish() {
        // Reception already happened → label hidden regardless of opening time (spec 039)
        val label = receptionDateLabel(pastDate, closingFuture, AppLanguage.EN, "5 PM", today)
        assertNull(label)
    }
```

Replace lines 67-71 (`pastDateWithTimeKorean`):

```kotlin
    @Test
    fun pastDateWithTimeKorean() {
        // Reception already happened → label hidden in Korean too (spec 039)
        val label = receptionDateLabel(pastDate, closingFuture, AppLanguage.KO, "5 PM", today)
        assertNull(label)
    }
```

Replace lines 100-103 (`pastDateWithoutTimeEnglish`):

```kotlin
    @Test
    fun pastDateWithoutTimeEnglish() {
        // Reception already happened → label hidden even without opening time (spec 039)
        val label = receptionDateLabel(pastDate, closingFuture, AppLanguage.EN, null, today)
        assertNull(label)
    }
```

Replace lines 117-123 (`pastDateWithinCurrentWeekEnglish`):

```kotlin
    @Test
    fun pastDateWithinCurrentWeekEnglish() {
        // Past date inside the current calendar week is still past → hidden (spec 039)
        val monday = LocalDate(2026, 4, 6) // Monday of this week; today is Wednesday
        val label = receptionDateLabel(monday, closingFuture, AppLanguage.EN, "5 PM", today)
        assertNull(label)
    }
```

Replace lines 125-130 (`pastDateWithinCurrentWeekNoTime`):

```kotlin
    @Test
    fun pastDateWithinCurrentWeekNoTime() {
        // Past date inside the current calendar week is still past → hidden (spec 039)
        val monday = LocalDate(2026, 4, 6)
        val label = receptionDateLabel(monday, closingFuture, AppLanguage.EN, null, today)
        assertNull(label)
    }
```

- [ ] **Step 2: Add three new tests covering the spec's acceptance criteria explicitly**

Append these three tests inside the `ReceptionDateLabelTest` class, just before the closing brace, after the existing "Edge cases: label hidden" section. They lock in spec AC #1–#4 (yesterday hidden in both languages; the boundary day is yesterday-not-today; opening time hides with the label).

```kotlin
    // ── Spec 039: hide past reception label ─────────────────────────────

    @Test
    fun yesterdayHiddenEnglish() {
        // Spec 039 AC #1: receptionDate = yesterday, exhibition still running → no label
        val yesterday = today.plus(-1, DateTimeUnit.DAY)
        val label = receptionDateLabel(yesterday, closingFuture, AppLanguage.EN, "5 PM", today)
        assertNull(label)
    }

    @Test
    fun yesterdayHiddenKorean() {
        // Spec 039 AC #4: same behavior in Korean
        val yesterday = today.plus(-1, DateTimeUnit.DAY)
        val label = receptionDateLabel(yesterday, closingFuture, AppLanguage.KO, "5 PM", today)
        assertNull(label)
    }

    @Test
    fun yesterdayHidesOpeningTimeToo() {
        // Spec 039: when the label hides, the inline opening time hides with it.
        // The function returning null is the single signal both pieces use to hide.
        val yesterday = today.plus(-1, DateTimeUnit.DAY)
        val withTime = receptionDateLabel(yesterday, closingFuture, AppLanguage.EN, "5 PM", today)
        val withoutTime = receptionDateLabel(yesterday, closingFuture, AppLanguage.EN, null, today)
        assertNull(withTime)
        assertNull(withoutTime)
    }
```

- [ ] **Step 3: Run the test file and confirm the five updated tests FAIL**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.data.model.ReceptionDateLabelTest" -i 2>&1 | tail -60
```

Expected: BUILD FAILED. At least these tests fail because `receptionDateLabel()` still returns formatted past-date strings: `pastDateWithTimeEnglish`, `pastDateWithTimeKorean`, `pastDateWithoutTimeEnglish`, `pastDateWithinCurrentWeekEnglish`, `pastDateWithinCurrentWeekNoTime`, `yesterdayHiddenEnglish`, `yesterdayHiddenKorean`, `yesterdayHidesOpeningTimeToo`.

The "tomorrow / today / weekday / hidden when exhibition ended / hidden when >1 week away" tests should still PASS — those branches of the function are not changing.

If you see a different failure (e.g. compile error from the test file), fix the test file before continuing — do not move on with a broken test source.

- [ ] **Step 4: Commit the failing tests**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add shared/src/commonTest/kotlin/com/gallr/shared/data/model/ReceptionDateLabelTest.kt
git commit -m "test(reception-label): assert past dates hide the label (spec 039)

Flip five existing tests that asserted formatted past-date strings,
and add three new tests covering spec AC for yesterday in EN, KO,
and with-time vs without-time. These all fail against the current
implementation and will pass once ReceptionLabel.kt is updated."
```

---

## Task 2: Implement the one-line behavior change (TDD green)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gallr/shared/data/model/ReceptionLabel.kt` (lines 59-67)

- [ ] **Step 1: Replace the "past but exhibition still running" branch body**

Open `shared/src/commonMain/kotlin/com/gallr/shared/data/model/ReceptionLabel.kt`. Find the `when` expression that ends around line 68. The current branch is:

```kotlin
        // Past but exhibition still running → show full date
        receptionDate < today -> {
            val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            if (lang == AppLanguage.KO) {
                "오프닝 ${receptionDate.monthNumber}월 ${receptionDate.dayOfMonth}일$timeSuffix"
            } else {
                "Opening ${months[receptionDate.monthNumber - 1]} ${receptionDate.dayOfMonth}$timeSuffix"
            }
        }
```

Replace those nine lines with:

```kotlin
        // Reception already happened → hide the label (spec 039)
        receptionDate < today -> null
```

Do not change anything else in the file. The function signature, the `closingDate < today` early return at the top, the "more than one week away" branch, the today / tomorrow / weekday branches, and the final `else -> null` all stay as they are.

After the edit, the `months` local array is no longer used anywhere. Confirm by searching the file — there should be zero remaining references to `months`. (The array was scoped inside the deleted branch, so removing the branch removes the array. There is no stray import to delete; the file does not import the `arrayOf` builder separately.)

- [ ] **Step 2: Run the full reception-label test suite and confirm everything passes**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.data.model.ReceptionDateLabelTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`. All ~24 tests in `ReceptionDateLabelTest` pass — both the five flipped tests, the three new yesterday tests, and the unchanged today / tomorrow / weekday / exhibition-ended / >1-week-away regression guards.

If any test fails, read the failure carefully. The most likely failure mode is a typo in the `when` branch you just edited — verify the branch body is exactly `null` (not `"null"` or empty braces) and the comment is on its own line.

- [ ] **Step 3: Commit the implementation**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add shared/src/commonMain/kotlin/com/gallr/shared/data/model/ReceptionLabel.kt
git commit -m "feat(reception-label): hide label when reception date is past (spec 039)

receptionDateLabel() now returns null for receptionDate < today.
The detail screen call site already conditions rendering on null,
so the inline opening time hides with the label automatically.

Closes spec 039."
```

---

## Task 3: Full-suite verification

The `shared` module is consumed by Android and iOS targets. The change is in `commonMain`, so all platforms see it. Verify nothing else in `shared` broke (e.g. another file using `receptionDateLabel` we didn't anticipate).

**Files:** none modified in this task — verification only.

- [ ] **Step 1: Sanity-check that no other source file depends on the formatted-past-date label**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr
grep -rn "receptionDateLabel" shared/src composeApp/src 2>/dev/null
```

Expected output is exactly two locations:

1. `shared/src/commonMain/kotlin/com/gallr/shared/data/model/ReceptionLabel.kt` — the function definition.
2. `composeApp/src/commonMain/kotlin/com/gallr/app/ui/detail/ExhibitionDetailScreen.kt` — the one call site at line 175.

(Test files are excluded by the paths above.) If any third location appears, stop and investigate — there may be another consumer whose contract we need to think about.

- [ ] **Step 2: Run the full shared-module test suite across all platforms**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:allTests 2>&1 | tail -40
```

Expected: `BUILD SUCCESSFUL`. This runs the JVM, Android, iOS Simulator Arm64, iOS Arm64, and iOS X64 test targets. The reception-label tests are in `commonTest` so they run on every target.

If iOS targets fail with linker / Kotlin/Native errors unrelated to this change (rare but possible on a fresh checkout), re-run just the JVM target:

```bash
./gradlew :shared:testDebugUnitTest 2>&1 | tail -20
```

— and note the iOS failure as pre-existing in the PR description. Do not paper over it; the change itself is `commonMain` and platform-agnostic, so a platform-specific build failure is not caused by this work.

- [ ] **Step 3: Manual smoke test instructions for the PR description**

Add this manual verification to the PR description (no command to run here — write it down so a reviewer can repeat it):

> **Manual smoke test:**
> 1. Open the gallr Android (or iOS) app in dark and light mode.
> 2. Navigate to an exhibition whose `receptionDate` is in the past and whose `closingDate` is still in the future. (If no such row exists in the current dataset, temporarily edit one in Supabase or in a local fixture.)
> 3. Confirm the detail page shows **no reception label** and **no inline opening time** below the title/dates.
> 4. Switch the in-app language between EN and KO; confirm both languages hide the label.
> 5. Navigate to an exhibition whose `receptionDate` is today; confirm the label reads "Opening today" (EN) / "오프닝 오늘" (KO).

- [ ] **Step 4: Push the branch**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git push -u origin 039-hide-past-reception-label
```

Stop here. PR creation is a separate step the user will trigger (e.g. via the `ship` skill) once they've reviewed the diff locally.

---

## Spec Coverage Self-Check

- Spec "Desired Behavior — Hide when `today > receptionDate`": Task 2 step 1 implements the rule; Tasks 1 step 1 + step 2 lock it in tests for both languages.
- Spec "Boundary is calendar-date based, not 24-hour": `receptionDateLabel()` already takes `today: LocalDate` (calendar date), and the implementation uses the date-only comparison `receptionDate < today`. No code change needed beyond Task 2.
- Spec "Display-layer fix only — no data model or pipeline changes": Tasks 1 and 2 touch only `ReceptionLabel.kt` and its test file. Task 3 step 1 verifies no other consumer.
- Spec "Applies to both English and Korean": Task 1 step 1 updates `pastDateWithTimeEnglish` + `pastDateWithTimeKorean`; Task 1 step 2 adds `yesterdayHiddenEnglish` + `yesterdayHiddenKorean`.
- Spec "`openingTime` is hidden together with the label": Task 1 step 2 `yesterdayHidesOpeningTimeToo` asserts this; the rendering side already wraps both in a single `if (receptionLabel != null)` block at `ExhibitionDetailScreen.kt:178`, which Task 3 step 1 confirms is the only call site.
- Spec "No other UI elements affected": Task 3 step 1 (grep) confirms only `ExhibitionDetailScreen.kt` consumes the function; all other detail-page elements pull from different model fields.
- Spec AC #1 yesterday hidden: `yesterdayHiddenEnglish` + `yesterdayHiddenKorean`.
- Spec AC #2 today shown: `todayWithTimeEnglish` / `todayWithTimeKorean` / `todayWithoutTimeEnglish` / `todayWithoutTimeKorean` (existing regression guards, unchanged).
- Spec AC #3 tomorrow shown: `tomorrowWithTimeEnglish` / `tomorrowWithTimeKorean` / `tomorrowWithoutTimeEnglish` (existing regression guards, unchanged).
- Spec AC #4 both languages identical: covered by all of the above EN/KO pairs.

No gaps. No placeholders. Function and method names are consistent (`receptionDateLabel` everywhere; no aliases). Tests reference the same `today` / `closingFuture` / `closingPast` fixture names used throughout the existing test file.
