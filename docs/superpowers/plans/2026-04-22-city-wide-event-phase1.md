# City-Wide Art Event — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an end-to-end, single-active-event experience: data layer (Supabase + GAS sync), an `Event` domain model + repository, a Featured-tab promoted card, and a new Event Detail screen. Phase 1 of a 2-phase rollout for city-wide art events.

**Architecture:** Mirrors the existing exhibitions stack — Supabase table + RLS public-read policy, dedicated GAS sync script, KMP `EventDto`/`Event`/`EventRepository` triple, Compose Multiplatform UI in `composeApp`. Navigation extends the app's existing **state-driven** model (a nullable `selectedEventId` in `App.kt`, mirroring the existing `selectedExhibition` pattern). No nav-graph routes are introduced.

**Tech Stack:** Kotlin 2.1.20 (KMP), Compose Multiplatform 1.8.0, Ktor 2.9 client, kotlinx.serialization 1.7, kotlinx-datetime 0.6, Supabase Postgres + REST, Google Apps Script V8.

**Spec:** `docs/superpowers/specs/2026-04-22-city-wide-biennale-phase1-design.md`

---

## File Structure

### Files to create

| Path | Responsibility |
|------|----------------|
| `supabase/migrations/013_create_events.sql` | Create `events` table, public-read RLS policy, add `event_id` to `exhibitions`, add partial index |
| `gas/SyncEvents.gs` | Sync the events Google Sheet to the `events` table; near-clone of `SyncExhibitions.gs` scoped to events |
| `shared/src/commonMain/kotlin/com/gallr/shared/data/model/Event.kt` | `Event` data class + `localizedName/locationLabel/description/dateRange` helpers |
| `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/EventDto.kt` | `@Serializable EventDto` with `toDomain(): Event?` |
| `shared/src/commonMain/kotlin/com/gallr/shared/data/network/EventApiClient.kt` | Ktor client wrapping `/rest/v1/events` and one `/exhibitions?event_id=eq.<id>` query |
| `shared/src/commonMain/kotlin/com/gallr/shared/repository/EventRepository.kt` | Interface: `getActiveEvents`, `getEventById`, `getExhibitionsForEvent` (all `Result<…>`) |
| `shared/src/commonMain/kotlin/com/gallr/shared/repository/EventRepositoryImpl.kt` | Calls `EventApiClient`, applies client-side active-event filter |
| `shared/src/commonMain/kotlin/com/gallr/shared/util/HexColor.kt` | `parseHexColor(s: String?): Long?` — parses `#RRGGBB` to a `0xFFRRGGBB` ARGB long; returns null on invalid |
| `shared/src/commonTest/kotlin/com/gallr/shared/data/model/EventTest.kt` | Tests for `Event` localized helpers and active-event semantics on a fake repo |
| `shared/src/commonTest/kotlin/com/gallr/shared/repository/EventRepositoryTest.kt` | Tests `EventRepositoryImpl.getActiveEvents` filter behavior using a fake `EventApiClient` |
| `shared/src/commonTest/kotlin/com/gallr/shared/util/HexColorTest.kt` | Tests `parseHexColor` for valid, invalid, and edge cases |
| `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/EventDetailViewModel.kt` | Loads + exposes event, deduped venues, exhibitions list |
| `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/EventPromotionCard.kt` | Featured-tab promoted card composable |
| `composeApp/src/commonMain/kotlin/com/gallr/app/ui/event/EventDetailScreen.kt` | Full-screen event detail composable |

### Files to modify

| Path | Change |
|------|--------|
| `shared/src/commonMain/kotlin/com/gallr/shared/data/model/Exhibition.kt` | Add `val eventId: String? = null` to constructor (last param) |
| `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/ExhibitionDto.kt` | Add `@SerialName("event_id") val eventId: String? = null`; map in `toDomain()` |
| `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt` | Inject `EventRepository`; add `activeEvent: StateFlow<Event?>`; load on init; update `factory` |
| `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/featured/FeaturedScreen.kt` | Render `EventPromotionCard` at top of feed when active event exists; route tap to new `onEventTap` callback |
| `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt` | Wire `EventRepository`; add `selectedEventId` state + `EventDetailScreen` branch in `AnimatedContent`; pass `onEventTap` |
| `gas/SyncExhibitions.gs` | Add `event_id` to `KNOWN_COLUMNS`; add fetch-events-once + skip-and-log if `event_id` references missing event |
| `composeApp/build.gradle.kts` (if `EventRepository` is wired via DI/factory there) | Likely no change — `App.kt` constructs repos at the root. Verify before editing. |

### Files to verify (no changes expected)

- `iosApp/iosApp/iOSApp.swift` and `composeApp/src/androidMain/...` — entry points construct repos and pass them to `App()`; both will need the new `eventRepository` parameter passed through. Touch only the call site.

---

## Task Breakdown

Tasks are ordered for vertical slices: data → domain → UI. Each task ends with a green test run + commit.

---

### Task 1: Supabase migration — events table + FK column

**Files:**
- Create: `supabase/migrations/013_create_events.sql`

- [ ] **Step 1: Write the migration**

Create the file with this content:

```sql
-- Migration: 013_create_events.sql
-- Run in the Supabase dashboard SQL editor:
-- https://supabase.com/dashboard → your project → SQL Editor

-- ── New table: events ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS events (
  id                  TEXT PRIMARY KEY,
  name_ko             TEXT NOT NULL,
  name_en             TEXT NOT NULL,
  description_ko      TEXT NOT NULL DEFAULT '',
  description_en      TEXT NOT NULL DEFAULT '',
  location_label_ko   TEXT NOT NULL,
  location_label_en   TEXT NOT NULL,
  start_date          DATE NOT NULL,
  end_date            DATE NOT NULL,
  brand_color         TEXT NOT NULL,
  accent_color        TEXT,
  ticket_url          TEXT,
  is_active           BOOLEAN NOT NULL DEFAULT true,
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE events ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Public read"
  ON events FOR SELECT
  USING (true);

-- ── exhibitions.event_id (nullable FK) ─────────────────────────────────────
ALTER TABLE exhibitions
  ADD COLUMN IF NOT EXISTS event_id TEXT REFERENCES events(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_exhibitions_event_id
  ON exhibitions(event_id) WHERE event_id IS NOT NULL;
```

- [ ] **Step 2: Apply migration in Supabase dashboard**

Manual step (not part of the build). Open Supabase → SQL Editor → paste the migration → run. Verify in the Tables view that `events` exists with all columns and `exhibitions.event_id` is nullable.

- [ ] **Step 3: Commit**

```bash
git add supabase/migrations/013_create_events.sql
git commit -m "feat(db): add events table and exhibitions.event_id FK"
```

---

### Task 2: HexColor utility + tests

**Files:**
- Create: `shared/src/commonMain/kotlin/com/gallr/shared/util/HexColor.kt`
- Test: `shared/src/commonTest/kotlin/com/gallr/shared/util/HexColorTest.kt`

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/gallr/shared/util/HexColorTest.kt`:

```kotlin
package com.gallr.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HexColorTest {

    @Test
    fun `parseHexColor returns ARGB long for valid 6-digit hex with hash`() {
        assertEquals(0xFF0099FFL, parseHexColor("#0099FF"))
    }

    @Test
    fun `parseHexColor returns ARGB long for valid 6-digit hex without hash`() {
        assertEquals(0xFFFF5C5CL, parseHexColor("FF5C5C"))
    }

    @Test
    fun `parseHexColor is case insensitive`() {
        assertEquals(0xFF0099FFL, parseHexColor("#0099ff"))
    }

    @Test
    fun `parseHexColor returns null for null input`() {
        assertNull(parseHexColor(null))
    }

    @Test
    fun `parseHexColor returns null for empty string`() {
        assertNull(parseHexColor(""))
    }

    @Test
    fun `parseHexColor returns null for non-hex characters`() {
        assertNull(parseHexColor("#ZZZZZZ"))
    }

    @Test
    fun `parseHexColor returns null for wrong length`() {
        assertNull(parseHexColor("#FFF"))
        assertNull(parseHexColor("#FF00"))
        assertNull(parseHexColor("#FF00FFFF"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.util.HexColorTest"
```

Expected: FAIL with "unresolved reference: parseHexColor".

- [ ] **Step 3: Implement HexColor.kt**

Create `shared/src/commonMain/kotlin/com/gallr/shared/util/HexColor.kt`:

```kotlin
package com.gallr.shared.util

/**
 * Parses a hex color string to an ARGB long (alpha forced to 0xFF).
 * Accepts "#RRGGBB" or "RRGGBB", case-insensitive. Returns null on any
 * malformed input — callers should fall back to a safe default.
 */
fun parseHexColor(input: String?): Long? {
    if (input.isNullOrBlank()) return null
    val cleaned = input.trim().removePrefix("#")
    if (cleaned.length != 6) return null
    val rgb = cleaned.toLongOrNull(radix = 16) ?: return null
    return 0xFF000000L or rgb
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.util.HexColorTest"
```

Expected: 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gallr/shared/util/HexColor.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/util/HexColorTest.kt
git commit -m "feat(shared): add parseHexColor utility for event brand colors"
```

---

### Task 3: Event domain model + tests

**Files:**
- Create: `shared/src/commonMain/kotlin/com/gallr/shared/data/model/Event.kt`
- Test: `shared/src/commonTest/kotlin/com/gallr/shared/data/model/EventTest.kt`

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/gallr/shared/data/model/EventTest.kt`:

```kotlin
package com.gallr.shared.data.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class EventTest {

    private val sample = Event(
        id = "loop-lab-busan-2025",
        nameKo = "루프랩 부산 2025",
        nameEn = "Loop Lab Busan 2025",
        descriptionKo = "한국어 설명",
        descriptionEn = "English description",
        locationLabelKo = "부산 전역",
        locationLabelEn = "Across Busan",
        startDate = LocalDate(2025, 4, 18),
        endDate = LocalDate(2025, 5, 10),
        brandColor = "#0099FF",
        accentColor = "#FF5C5C",
        ticketUrl = "https://example.com/tickets",
        isActive = true,
    )

    @Test
    fun `localizedName returns Korean for KO`() {
        assertEquals("루프랩 부산 2025", sample.localizedName(AppLanguage.KO))
    }

    @Test
    fun `localizedName returns English for EN`() {
        assertEquals("Loop Lab Busan 2025", sample.localizedName(AppLanguage.EN))
    }

    @Test
    fun `localizedName falls back to Korean when English is empty`() {
        val koOnly = sample.copy(nameEn = "")
        assertEquals("루프랩 부산 2025", koOnly.localizedName(AppLanguage.EN))
    }

    @Test
    fun `localizedLocationLabel falls back to Korean when English is empty`() {
        val koOnly = sample.copy(locationLabelEn = "")
        assertEquals("부산 전역", koOnly.localizedLocationLabel(AppLanguage.EN))
    }

    @Test
    fun `localizedDescription returns English when both present`() {
        assertEquals("English description", sample.localizedDescription(AppLanguage.EN))
    }

    @Test
    fun `isActiveOn returns true when today equals start date`() {
        assertEquals(true, sample.isActiveOn(LocalDate(2025, 4, 18)))
    }

    @Test
    fun `isActiveOn returns true when today equals end date`() {
        assertEquals(true, sample.isActiveOn(LocalDate(2025, 5, 10)))
    }

    @Test
    fun `isActiveOn returns false the day after end date`() {
        assertEquals(false, sample.isActiveOn(LocalDate(2025, 5, 11)))
    }

    @Test
    fun `isActiveOn returns false the day before start date`() {
        assertEquals(false, sample.isActiveOn(LocalDate(2025, 4, 17)))
    }

    @Test
    fun `isActiveOn returns false when isActive flag is false`() {
        val killed = sample.copy(isActive = false)
        assertEquals(false, killed.isActiveOn(LocalDate(2025, 4, 20)))
    }

    @Test
    fun `nameLastToken extracts trailing whitespace-separated word`() {
        assertEquals("BUSAN", Event.nameLastToken("LOOP LAB BUSAN"))
        assertEquals("2025", Event.nameLastToken("Loop Lab Busan 2025"))
        assertEquals("Solo", Event.nameLastToken("Solo"))
        assertEquals("", Event.nameLastToken(""))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.data.model.EventTest"
```

Expected: FAIL with "unresolved reference: Event".

- [ ] **Step 3: Implement Event.kt**

Create `shared/src/commonMain/kotlin/com/gallr/shared/data/model/Event.kt`:

```kotlin
package com.gallr.shared.data.model

import kotlinx.datetime.LocalDate

data class Event(
    val id: String,
    val nameKo: String,
    val nameEn: String,
    val descriptionKo: String,
    val descriptionEn: String,
    val locationLabelKo: String,
    val locationLabelEn: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val brandColor: String,
    val accentColor: String?,
    val ticketUrl: String?,
    val isActive: Boolean,
) {
    fun localizedName(lang: AppLanguage): String = when (lang) {
        AppLanguage.EN -> nameEn.ifEmpty { nameKo }
        AppLanguage.KO -> nameKo
    }

    fun localizedDescription(lang: AppLanguage): String = when (lang) {
        AppLanguage.EN -> descriptionEn.ifEmpty { descriptionKo }
        AppLanguage.KO -> descriptionKo
    }

    fun localizedLocationLabel(lang: AppLanguage): String = when (lang) {
        AppLanguage.EN -> locationLabelEn.ifEmpty { locationLabelKo }
        AppLanguage.KO -> locationLabelKo
    }

    fun isActiveOn(today: LocalDate): Boolean =
        isActive && today >= startDate && today <= endDate

    companion object {
        /**
         * Returns the last whitespace-separated token of a string.
         * Used to render the trailing word of an event name in the accent color
         * (e.g., "Loop Lab BUSAN" — "BUSAN" gets accent color treatment).
         */
        fun nameLastToken(name: String): String {
            if (name.isEmpty()) return ""
            return name.trim().split(Regex("\\s+")).lastOrNull() ?: ""
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.data.model.EventTest"
```

Expected: 11 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gallr/shared/data/model/Event.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/data/model/EventTest.kt
git commit -m "feat(shared): add Event model with localization and active-window helpers"
```

---

### Task 4: EventDto with toDomain mapping

**Files:**
- Create: `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/EventDto.kt`
- Test: extend `shared/src/commonTest/kotlin/com/gallr/shared/data/model/EventTest.kt` with a DTO-mapping test

- [ ] **Step 1: Add the failing DTO test**

Append to `shared/src/commonTest/kotlin/com/gallr/shared/data/model/EventTest.kt` (inside the `EventTest` class, after the existing tests):

```kotlin
    @Test
    fun `EventDto toDomain returns null when start_date is malformed`() {
        val dto = com.gallr.shared.data.network.dto.EventDto(
            id = "x",
            nameKo = "x", nameEn = "x",
            locationLabelKo = "x", locationLabelEn = "x",
            startDate = "not-a-date",
            endDate = "2025-05-10",
            brandColor = "#000000",
        )
        kotlin.test.assertNull(dto.toDomain())
    }

    @Test
    fun `EventDto toDomain returns Event with parsed dates and defaults`() {
        val dto = com.gallr.shared.data.network.dto.EventDto(
            id = "loop-lab-busan-2025",
            nameKo = "루프랩 부산 2025", nameEn = "Loop Lab Busan 2025",
            locationLabelKo = "부산 전역", locationLabelEn = "Across Busan",
            startDate = "2025-04-18",
            endDate = "2025-05-10",
            brandColor = "#0099FF",
        )
        val event = dto.toDomain()!!
        kotlin.test.assertEquals(LocalDate(2025, 4, 18), event.startDate)
        kotlin.test.assertEquals(LocalDate(2025, 5, 10), event.endDate)
        kotlin.test.assertEquals("", event.descriptionKo)  // default
        kotlin.test.assertEquals(true, event.isActive)     // default
        kotlin.test.assertEquals(null, event.accentColor)  // optional
    }
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.data.model.EventTest"
```

Expected: FAIL with "unresolved reference: EventDto".

- [ ] **Step 3: Implement EventDto.kt**

Create `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/EventDto.kt`:

```kotlin
package com.gallr.shared.data.network.dto

import com.gallr.shared.data.model.Event
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EventDto(
    val id: String,
    @SerialName("name_ko") val nameKo: String,
    @SerialName("name_en") val nameEn: String,
    @SerialName("description_ko") val descriptionKo: String = "",
    @SerialName("description_en") val descriptionEn: String = "",
    @SerialName("location_label_ko") val locationLabelKo: String,
    @SerialName("location_label_en") val locationLabelEn: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    @SerialName("brand_color") val brandColor: String,
    @SerialName("accent_color") val accentColor: String? = null,
    @SerialName("ticket_url") val ticketUrl: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
) {
    fun toDomain(): Event? {
        val start = try { LocalDate.parse(startDate) } catch (_: Exception) { return null }
        val end = try { LocalDate.parse(endDate) } catch (_: Exception) { return null }
        return Event(
            id = id,
            nameKo = nameKo,
            nameEn = nameEn,
            descriptionKo = descriptionKo,
            descriptionEn = descriptionEn,
            locationLabelKo = locationLabelKo,
            locationLabelEn = locationLabelEn,
            startDate = start,
            endDate = end,
            brandColor = brandColor,
            accentColor = accentColor,
            ticketUrl = ticketUrl,
            isActive = isActive,
        )
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.data.model.EventTest"
```

Expected: 13 tests PASS (11 from Task 3 + 2 new).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/EventDto.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/data/model/EventTest.kt
git commit -m "feat(shared): add EventDto with toDomain mapping"
```

---

### Task 5: Add event_id to Exhibition model + ExhibitionDto

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gallr/shared/data/model/Exhibition.kt`
- Modify: `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/ExhibitionDto.kt`

- [ ] **Step 1: Add `eventId` to `Exhibition`**

In `shared/src/commonMain/kotlin/com/gallr/shared/data/model/Exhibition.kt`, add `eventId` as the LAST constructor parameter (preserving existing parameter order so positional callers don't break):

Replace the closing of the data class constructor:

```kotlin
    val openingTime: String? = null,
) {
```

with:

```kotlin
    val openingTime: String? = null,
    val eventId: String? = null,
) {
```

- [ ] **Step 2: Add `event_id` to `ExhibitionDto` and map it in `toDomain()`**

In `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/ExhibitionDto.kt`, add the field at the end of the constructor:

Replace:

```kotlin
    @SerialName("opening_time") val openingTime: String? = null,
) {
```

with:

```kotlin
    @SerialName("opening_time") val openingTime: String? = null,
    @SerialName("event_id") val eventId: String? = null,
) {
```

Then in `toDomain()`, add `eventId = eventId,` as the last line of the `Exhibition(...)` constructor call (right before the closing `)`):

```kotlin
            openingTime = openingTime,
            eventId = eventId,
        )
```

- [ ] **Step 3: Verify the existing exhibition tests still pass**

```bash
./gradlew :shared:testDebugUnitTest
```

Expected: all tests PASS (no exhibition tests reference `eventId` yet, default `null` keeps them passing).

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gallr/shared/data/model/Exhibition.kt \
        shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/ExhibitionDto.kt
git commit -m "feat(shared): add nullable eventId to Exhibition model and DTO"
```

---

### Task 6: EventApiClient

**Files:**
- Create: `shared/src/commonMain/kotlin/com/gallr/shared/data/network/EventApiClient.kt`

This task has no unit tests (matches `ExhibitionApiClient` — networking is exercised via repository tests in Task 7 with a fake client). Keep the API surface tight so the fake is easy to write.

- [ ] **Step 1: Write `EventApiClient`**

Create `shared/src/commonMain/kotlin/com/gallr/shared/data/network/EventApiClient.kt`:

```kotlin
package com.gallr.shared.data.network

import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.dto.EventDto
import com.gallr.shared.data.network.dto.ExhibitionDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.SIMPLE
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

interface EventApi {
    suspend fun fetchEvents(): List<Event>
    suspend fun fetchEventById(id: String): Event?
    suspend fun fetchExhibitionsForEvent(id: String): List<Exhibition>
}

class EventApiClient(
    supabaseUrl: String,
    anonKey: String,
) : EventApi {
    private val restBase = "$supabaseUrl/rest/v1"

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
        install(Logging) {
            logger = Logger.SIMPLE
            level = LogLevel.INFO
        }
        defaultRequest {
            headers.append("apikey", anonKey)
            headers.append("Authorization", "Bearer $anonKey")
        }
    }

    override suspend fun fetchEvents(): List<Event> =
        client.get("$restBase/events?select=*")
            .body<List<EventDto>>()
            .mapNotNull { it.toDomain() }

    override suspend fun fetchEventById(id: String): Event? =
        client.get("$restBase/events?select=*&id=eq.$id&limit=1")
            .body<List<EventDto>>()
            .firstOrNull()
            ?.toDomain()

    override suspend fun fetchExhibitionsForEvent(id: String): List<Exhibition> =
        client.get("$restBase/exhibitions?select=*&event_id=eq.$id")
            .body<List<ExhibitionDto>>()
            .mapNotNull { it.toDomain() }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew :shared:compileKotlinMetadata
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gallr/shared/data/network/EventApiClient.kt
git commit -m "feat(shared): add EventApiClient and EventApi interface"
```

---

### Task 7: EventRepository + tests

**Files:**
- Create: `shared/src/commonMain/kotlin/com/gallr/shared/repository/EventRepository.kt`
- Create: `shared/src/commonMain/kotlin/com/gallr/shared/repository/EventRepositoryImpl.kt`
- Test: `shared/src/commonTest/kotlin/com/gallr/shared/repository/EventRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/gallr/shared/repository/EventRepositoryTest.kt`:

```kotlin
package com.gallr.shared.repository

import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.EventApi
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventRepositoryTest {

    private fun event(
        id: String,
        start: LocalDate,
        end: LocalDate,
        active: Boolean = true,
    ) = Event(
        id = id,
        nameKo = id, nameEn = id,
        descriptionKo = "", descriptionEn = "",
        locationLabelKo = "x", locationLabelEn = "x",
        startDate = start, endDate = end,
        brandColor = "#000000", accentColor = null, ticketUrl = null,
        isActive = active,
    )

    private class FakeEventApi(
        private val events: List<Event>,
        private val exhibitionsByEventId: Map<String, List<Exhibition>> = emptyMap(),
    ) : EventApi {
        override suspend fun fetchEvents(): List<Event> = events
        override suspend fun fetchEventById(id: String): Event? = events.firstOrNull { it.id == id }
        override suspend fun fetchExhibitionsForEvent(id: String): List<Exhibition> =
            exhibitionsByEventId[id] ?: emptyList()
    }

    private val today = LocalDate(2025, 4, 22)

    @Test
    fun `getActiveEvents includes event whose date range covers today`() = runTest {
        val active = event("a", LocalDate(2025, 4, 18), LocalDate(2025, 5, 10))
        val repo = EventRepositoryImpl(FakeEventApi(listOf(active))) { today }
        val result = repo.getActiveEvents().getOrThrow()
        assertEquals(listOf("a"), result.map { it.id })
    }

    @Test
    fun `getActiveEvents excludes event whose end_date is before today`() = runTest {
        val past = event("p", LocalDate(2025, 1, 1), LocalDate(2025, 4, 21))
        val repo = EventRepositoryImpl(FakeEventApi(listOf(past))) { today }
        assertTrue(repo.getActiveEvents().getOrThrow().isEmpty())
    }

    @Test
    fun `getActiveEvents excludes event whose start_date is after today`() = runTest {
        val future = event("f", LocalDate(2025, 4, 23), LocalDate(2025, 5, 30))
        val repo = EventRepositoryImpl(FakeEventApi(listOf(future))) { today }
        assertTrue(repo.getActiveEvents().getOrThrow().isEmpty())
    }

    @Test
    fun `getActiveEvents excludes event with isActive false even when in date range`() = runTest {
        val killed = event("k", LocalDate(2025, 4, 18), LocalDate(2025, 5, 10), active = false)
        val repo = EventRepositoryImpl(FakeEventApi(listOf(killed))) { today }
        assertTrue(repo.getActiveEvents().getOrThrow().isEmpty())
    }

    @Test
    fun `getActiveEvents sorts by start_date ascending`() = runTest {
        val later = event("later", LocalDate(2025, 4, 20), LocalDate(2025, 5, 10))
        val earlier = event("earlier", LocalDate(2025, 4, 18), LocalDate(2025, 5, 10))
        val repo = EventRepositoryImpl(FakeEventApi(listOf(later, earlier))) { today }
        val result = repo.getActiveEvents().getOrThrow()
        assertEquals(listOf("earlier", "later"), result.map { it.id })
    }

    @Test
    fun `getEventById returns null when event missing`() = runTest {
        val repo = EventRepositoryImpl(FakeEventApi(emptyList())) { today }
        assertNull(repo.getEventById("missing").getOrThrow())
    }

    @Test
    fun `getEventById returns the event when present`() = runTest {
        val e = event("a", LocalDate(2025, 4, 18), LocalDate(2025, 5, 10))
        val repo = EventRepositoryImpl(FakeEventApi(listOf(e))) { today }
        assertNotNull(repo.getEventById("a").getOrThrow())
    }

    @Test
    fun `getExhibitionsForEvent returns the exhibitions for the event id`() = runTest {
        val e = event("a", LocalDate(2025, 4, 18), LocalDate(2025, 5, 10))
        val exh = listOf(stubExhibition("ex1", "a"), stubExhibition("ex2", "a"))
        val repo = EventRepositoryImpl(
            FakeEventApi(events = listOf(e), exhibitionsByEventId = mapOf("a" to exh))
        ) { today }
        assertEquals(listOf("ex1", "ex2"), repo.getExhibitionsForEvent("a").getOrThrow().map { it.id })
    }

    private fun stubExhibition(id: String, eventId: String) = Exhibition(
        id = id,
        nameKo = id, nameEn = id,
        venueNameKo = "venue", venueNameEn = "venue",
        cityKo = "city", cityEn = "city",
        regionKo = "region", regionEn = "region",
        openingDate = LocalDate(2025, 4, 18),
        closingDate = LocalDate(2025, 5, 10),
        isFeatured = false, isEditorsPick = false,
        latitude = null, longitude = null,
        descriptionKo = "", descriptionEn = "",
        addressKo = "", addressEn = "",
        coverImageUrl = null,
        eventId = eventId,
    )
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.repository.EventRepositoryTest"
```

Expected: FAIL with "unresolved reference: EventRepositoryImpl".

- [ ] **Step 3: Implement EventRepository.kt and EventRepositoryImpl.kt**

Create `shared/src/commonMain/kotlin/com/gallr/shared/repository/EventRepository.kt`:

```kotlin
package com.gallr.shared.repository

import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition

interface EventRepository {
    suspend fun getActiveEvents(): Result<List<Event>>
    suspend fun getEventById(id: String): Result<Event?>
    suspend fun getExhibitionsForEvent(id: String): Result<List<Exhibition>>
}
```

Create `shared/src/commonMain/kotlin/com/gallr/shared/repository/EventRepositoryImpl.kt`:

```kotlin
package com.gallr.shared.repository

import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.EventApi
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

class EventRepositoryImpl(
    private val api: EventApi,
    private val nowProvider: () -> LocalDate = {
        Clock.System.todayIn(TimeZone.of("Asia/Seoul"))
    },
) : EventRepository {

    override suspend fun getActiveEvents(): Result<List<Event>> = runCatching {
        val today = nowProvider()
        api.fetchEvents()
            .filter { it.isActiveOn(today) }
            .sortedBy { it.startDate }
    }

    override suspend fun getEventById(id: String): Result<Event?> =
        runCatching { api.fetchEventById(id) }

    override suspend fun getExhibitionsForEvent(id: String): Result<List<Exhibition>> =
        runCatching { api.fetchExhibitionsForEvent(id) }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.repository.EventRepositoryTest"
```

Expected: 8 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gallr/shared/repository/EventRepository.kt \
        shared/src/commonMain/kotlin/com/gallr/shared/repository/EventRepositoryImpl.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/repository/EventRepositoryTest.kt
git commit -m "feat(shared): add EventRepository with active-window filtering and sorting"
```

---

### Task 8: Wire EventRepository into TabsViewModel and App.kt

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt` (or wherever `App(...)` is constructed)
- Modify: `iosApp/iosApp/iOSApp.swift` (or `ContentView.swift`) — only if it instantiates the Compose entry point and passes repos
- Modify: `composeApp/src/desktopMain/...` if a desktop entry exists (verify with `find composeApp -name "*.kt" -path "*Main.kt"`)

This task adds the repo wiring with no UI rendering yet — UI lands in Tasks 9 and 10.

- [ ] **Step 1: Add `activeEvent` state to `TabsViewModel`**

In `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt`:

Add an import at the top:

```kotlin
import com.gallr.shared.data.model.Event
import com.gallr.shared.repository.EventRepository
```

Change the constructor signature to add `eventRepository`:

```kotlin
class TabsViewModel(
    private val exhibitionRepository: ExhibitionRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val languageRepository: LanguageRepository,
    private val themeRepository: ThemeRepository,
    private val eventRepository: EventRepository,
) : ViewModel() {
```

In the section just below `_isRefreshing` (around the "Search" comment block — pick a clear spot, recommend right after `_isRefreshing`), add:

```kotlin
    // ── Active event ─────────────────────────────────────────────────────────

    private val _activeEvent = MutableStateFlow<Event?>(null)
    val activeEvent: StateFlow<Event?> = _activeEvent

    fun loadActiveEvent() {
        viewModelScope.launch {
            eventRepository.getActiveEvents()
                .onSuccess { events -> _activeEvent.value = events.firstOrNull() }
                .onFailure {
                    println("ERROR [TabsViewModel] loadActiveEvent: ${it.message}")
                    _activeEvent.value = null
                }
        }
    }
```

In the `init { … }` block at the bottom, add a call:

```kotlin
    init {
        loadFeaturedExhibitions()
        loadAllExhibitions()
        loadActiveEvent()
    }
```

In `refresh()`, add `loadActiveEvent()`:

```kotlin
    fun refresh() {
        loadFeaturedExhibitions()
        loadAllExhibitions()
        loadActiveEvent()
    }
```

Update the `companion object`'s `factory` function to take `eventRepository` and pass it through:

```kotlin
    companion object {
        fun factory(
            exhibitionRepository: ExhibitionRepository,
            bookmarkRepository: BookmarkRepository,
            languageRepository: LanguageRepository,
            themeRepository: ThemeRepository,
            eventRepository: EventRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TabsViewModel(
                    exhibitionRepository,
                    bookmarkRepository,
                    languageRepository,
                    themeRepository,
                    eventRepository,
                )
            }
        }
    }
```

- [ ] **Step 2: Add `eventRepository` to `App.kt` parameter list and forward to factory**

In `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`, add to the `App(...)` parameter list (insert near the other repository parameters):

```kotlin
    eventRepository: EventRepository,
```

Add the import near the other repository imports:

```kotlin
import com.gallr.shared.repository.EventRepository
```

Update the `viewModel(...)` factory call:

```kotlin
    val viewModel: TabsViewModel = viewModel(
        factory = TabsViewModel.factory(
            exhibitionRepository,
            syncBookmarkRepository,
            languageRepository,
            themeRepository,
            eventRepository,
        ),
    )
```

- [ ] **Step 3: Update each platform entry point to construct and pass `EventRepository`**

Find every call site of `App(...)`:

```bash
grep -rn "App(" composeApp/src --include="*.kt" | grep -v "^.*build/"
grep -rn "App(" iosApp --include="*.swift"
```

For each call site, construct an `EventApiClient` (using the same `supabaseUrl` and `anonKey` already used for `ExhibitionApiClient`) and an `EventRepositoryImpl` around it, then pass `eventRepository` to `App(...)`. Mirror the existing exhibition wiring exactly.

Example for an Android entry point that already builds `ExhibitionRepositoryImpl(ExhibitionApiClient(...))`:

```kotlin
val eventApiClient = EventApiClient(supabaseUrl = SUPABASE_URL, anonKey = SUPABASE_ANON_KEY)
val eventRepository = EventRepositoryImpl(eventApiClient)

App(
    exhibitionRepository = exhibitionRepository,
    // ...existing params...
    eventRepository = eventRepository,
)
```

iOS Swift side: if the entry point invokes the Compose `App` via a Kotlin shim that constructs repos, modify the shim to accept/construct `EventRepository`. If iOS passes repos directly to a Kotlin-exported function, add the parameter there.

- [ ] **Step 4: Verify the project still builds**

```bash
./gradlew :composeApp:compileCommonMainKotlinMetadata :shared:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL; all existing tests still PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt \
        composeApp/src/commonMain/kotlin/com/gallr/app/App.kt \
        composeApp/src/androidMain composeApp/src/iosMain iosApp
git commit -m "feat(app): wire EventRepository through TabsViewModel and App entry points"
```

---

### Task 9: EventPromotionCard composable

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/EventPromotionCard.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/featured/FeaturedScreen.kt`

This is a UI composable; tests are visual/manual, not unit. Keep the file focused: rendering only, no data fetching.

- [ ] **Step 1: Implement `EventPromotionCard`**

Create `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/EventPromotionCard.kt`:

```kotlin
package com.gallr.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Event
import com.gallr.shared.util.parseHexColor

@Composable
fun EventPromotionCard(
    event: Event,
    lang: AppLanguage,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val brand = parseHexColor(event.brandColor)?.let { Color(it.toULong()) } ?: Color.Black
    val accent = parseHexColor(event.accentColor)?.let { Color(it.toULong()) }

    val name = event.localizedName(lang)
    val lastToken = Event.nameLastToken(name)
    val nameDisplay = buildAnnotatedString {
        if (accent != null && lastToken.isNotEmpty() && name.endsWith(lastToken)) {
            append(name.dropLast(lastToken.length))
            withStyle(SpanStyle(color = accent)) { append(lastToken) }
        } else {
            append(name)
        }
    }

    val eyebrow = if (lang == AppLanguage.KO) "지금 진행 중 · ART EVENT" else "NOW ON · ART EVENT"
    val meta = "${event.localizedDateRange(lang)} · ${event.localizedLocationLabel(lang)}"

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(brand)
            .border(1.dp, Color.Black)
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = eyebrow,
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = nameDisplay,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = meta,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                text = "→",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
            )
        }
    }
}

private fun Event.localizedDateRange(lang: AppLanguage): String {
    val from = startDate
    val to = endDate
    return when (lang) {
        AppLanguage.KO -> "${from.year}.${from.monthNumber.toString().padStart(2, '0')}.${from.dayOfMonth.toString().padStart(2, '0')} – ${to.year}.${to.monthNumber.toString().padStart(2, '0')}.${to.dayOfMonth.toString().padStart(2, '0')}"
        AppLanguage.EN -> {
            val months = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
            "${months[from.monthNumber - 1]} ${from.dayOfMonth} – ${months[to.monthNumber - 1]} ${to.dayOfMonth}, ${to.year}"
        }
    }
}
```

- [ ] **Step 2: Render the card in `FeaturedScreen`**

In `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/featured/FeaturedScreen.kt`:

Add the import:

```kotlin
import com.gallr.app.ui.components.EventPromotionCard
import com.gallr.shared.data.model.Event
```

Change the function signature to accept the new tap callback (one new parameter, typed `(String) -> Unit`):

```kotlin
@Composable
fun FeaturedScreen(
    viewModel: TabsViewModel,
    onExhibitionTap: (Exhibition) -> Unit,
    onEventTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
```

Inside the composable body, add this line right after the existing `val isRefreshing by viewModel.isRefreshing.collectAsState()`:

```kotlin
    val activeEvent by viewModel.activeEvent.collectAsState()
```

Then in the `Column { ... }` body, render the card *above* the existing `Text("추천" / "FEATURED")` heading. Replace the section that currently looks like:

```kotlin
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = if (lang == AppLanguage.KO) "추천" else "FEATURED",
            ...
        )
        Spacer(Modifier.height(GallrSpacing.sm))
```

with:

```kotlin
    Column(modifier = modifier.fillMaxSize()) {
        activeEvent?.let { event ->
            EventPromotionCard(
                event = event,
                lang = lang,
                onTap = { onEventTap(event.id) },
                modifier = Modifier.padding(
                    horizontal = GallrSpacing.md,
                    vertical = GallrSpacing.sm,
                ),
            )
        }
        Text(
            text = if (lang == AppLanguage.KO) "추천" else "FEATURED",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(
                horizontal = GallrSpacing.screenMargin,
                vertical = GallrSpacing.sm,
            ),
        )
        Spacer(Modifier.height(GallrSpacing.sm))
```

- [ ] **Step 3: Update the `App.kt` call site to supply `onEventTap`**

In `App.kt`, the `FeaturedScreen(...)` call inside the `when (tab) { 0 -> FeaturedScreen(...) }` branch now needs `onEventTap`. Add a placeholder for now (Task 10 wires it to navigation):

Find:

```kotlin
                            0 -> FeaturedScreen(
                                viewModel = viewModel,
                                onExhibitionTap = { selectedExhibition = it },
                                modifier = Modifier.padding(innerPadding),
                            )
```

Change to:

```kotlin
                            0 -> FeaturedScreen(
                                viewModel = viewModel,
                                onExhibitionTap = { selectedExhibition = it },
                                onEventTap = { /* wired in Task 10 */ },
                                modifier = Modifier.padding(innerPadding),
                            )
```

- [ ] **Step 4: Verify the project compiles**

```bash
./gradlew :composeApp:compileCommonMainKotlinMetadata
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/EventPromotionCard.kt \
        composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/featured/FeaturedScreen.kt \
        composeApp/src/commonMain/kotlin/com/gallr/app/App.kt
git commit -m "feat(featured): render EventPromotionCard above the feed when an event is active"
```

---

### Task 10: EventDetailViewModel + EventDetailScreen + navigation wiring

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/EventDetailViewModel.kt`
- Create: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/event/EventDetailScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`

- [ ] **Step 1: Implement `EventDetailViewModel`**

Create `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/EventDetailViewModel.kt`:

```kotlin
package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.repository.EventRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class EventDetailViewModel(
    private val eventId: String,
    private val eventRepository: EventRepository,
) : ViewModel() {

    private val _event = MutableStateFlow<Event?>(null)
    val event: StateFlow<Event?> = _event

    private val _exhibitions = MutableStateFlow<List<Exhibition>>(emptyList())
    val exhibitions: StateFlow<List<Exhibition>> = _exhibitions

    private val _venuesKo = MutableStateFlow<List<String>>(emptyList())
    val venuesKo: StateFlow<List<String>> = _venuesKo

    private val _venuesEn = MutableStateFlow<List<String>>(emptyList())
    val venuesEn: StateFlow<List<String>> = _venuesEn

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            eventRepository.getEventById(eventId)
                .onSuccess { _event.value = it }
                .onFailure {
                    _error.value = it.message ?: "load_event_failed"
                    _isLoading.value = false
                    return@launch
                }

            eventRepository.getExhibitionsForEvent(eventId)
                .onSuccess { list ->
                    _exhibitions.value = list.sortedBy { it.openingDate }
                    _venuesKo.value = list.map { it.venueNameKo }.distinct().sorted()
                    _venuesEn.value = list.map { it.venueNameEn.ifEmpty { it.venueNameKo } }.distinct().sorted()
                }
                .onFailure { _error.value = it.message ?: "load_exhibitions_failed" }

            _isLoading.value = false
        }
    }

    companion object {
        fun factory(
            eventId: String,
            eventRepository: EventRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { EventDetailViewModel(eventId, eventRepository) }
        }
    }
}
```

- [ ] **Step 2: Implement `EventDetailScreen`**

Create `composeApp/src/commonMain/kotlin/com/gallr/app/ui/event/EventDetailScreen.kt`:

```kotlin
package com.gallr.app.ui.event

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.gallr.app.viewmodel.EventDetailViewModel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.util.parseHexColor

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EventDetailScreen(
    viewModel: EventDetailViewModel,
    lang: AppLanguage,
    onBack: () -> Unit,
    onExhibitionTap: (Exhibition) -> Unit,
    modifier: Modifier = Modifier,
) {
    val event by viewModel.event.collectAsState()
    val exhibitions by viewModel.exhibitions.collectAsState()
    val venuesKo by viewModel.venuesKo.collectAsState()
    val venuesEn by viewModel.venuesEn.collectAsState()

    val brand = event?.brandColor?.let { parseHexColor(it) }?.let { Color(it.toULong()) } ?: Color.Black
    val accent = event?.accentColor?.let { parseHexColor(it) }?.let { Color(it.toULong()) }
    val venues = if (lang == AppLanguage.KO) venuesKo else venuesEn

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        // ── Top bar ──────────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .clickable(onClick = onBack),
        ) {
            Text(text = "←", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.padding(start = 8.dp))
            Text(
                text = if (lang == AppLanguage.KO) "아트페어" else "ART EVENT",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        HorizontalDivider(color = Color.Black, thickness = 1.dp)

        val current = event
        if (current == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (lang == AppLanguage.KO) "불러오는 중…" else "Loading…",
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {

            // ── Branded header ────────────────────────────────────────────
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .background(brand)
                        .padding(16.dp),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    Column {
                        Text(
                            text = if (lang == AppLanguage.KO)
                                "도시 전역 · ART EVENT · ${current.localizedLocationLabel(lang)}"
                            else
                                "CITY-WIDE · ART EVENT · ${current.localizedLocationLabel(lang)}",
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = renderEventName(current.localizedName(lang), accent),
                            color = Color.White,
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${formatDateRange(current, lang)} · ${current.localizedLocationLabel(lang)}",
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            // ── About section ─────────────────────────────────────────────
            val description = current.localizedDescription(lang)
            if (description.isNotBlank()) {
                item {
                    SectionLabel(if (lang == AppLanguage.KO) "소개" else "ABOUT")
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            // ── Participating Venues section ──────────────────────────────
            if (venues.isNotEmpty()) {
                item {
                    SectionLabel(if (lang == AppLanguage.KO) "참여 갤러리" else "PARTICIPATING VENUES")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        venues.forEach { venue ->
                            Box(
                                modifier = Modifier
                                    .border(1.dp, brand)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = venue,
                                    color = brand,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                )
                            }
                        }
                    }
                }
            }

            // ── Exhibitions section ───────────────────────────────────────
            if (exhibitions.isNotEmpty()) {
                item {
                    SectionLabel(if (lang == AppLanguage.KO) "전시" else "EXHIBITIONS")
                }
                items(exhibitions, key = { it.id }) { exhibition ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .border(1.dp, brand)
                            .clickable(onClick = { onExhibitionTap(exhibition) })
                            .padding(8.dp),
                    ) {
                        Column {
                            Text(
                                text = exhibition.localizedVenueName(lang),
                                color = brand,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = exhibition.localizedName(lang),
                                color = MaterialTheme.colorScheme.onBackground,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = exhibition.localizedDateRange(lang),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black),
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private fun renderEventName(name: String, accent: Color?) = buildAnnotatedString {
    val lastToken = Event.nameLastToken(name)
    if (accent != null && lastToken.isNotEmpty() && name.endsWith(lastToken)) {
        append(name.dropLast(lastToken.length))
        withStyle(SpanStyle(color = accent)) { append(lastToken) }
    } else {
        append(name)
    }
}

private fun formatDateRange(event: Event, lang: AppLanguage): String {
    val from = event.startDate
    val to = event.endDate
    return when (lang) {
        AppLanguage.KO -> "${from.year}.${from.monthNumber.toString().padStart(2, '0')}.${from.dayOfMonth.toString().padStart(2, '0')} – ${to.year}.${to.monthNumber.toString().padStart(2, '0')}.${to.dayOfMonth.toString().padStart(2, '0')}"
        AppLanguage.EN -> {
            val months = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
            "${months[from.monthNumber - 1]} ${from.dayOfMonth} – ${months[to.monthNumber - 1]} ${to.dayOfMonth}, ${to.year}"
        }
    }
}
```

- [ ] **Step 3: Wire navigation in `App.kt`**

In `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`:

Add imports:

```kotlin
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gallr.app.ui.event.EventDetailScreen
import com.gallr.app.viewmodel.EventDetailViewModel
```

(`viewModel` is already imported.)

Inside the `App` body, near `var selectedExhibition by remember { mutableStateOf<Exhibition?>(null) }`, add:

```kotlin
        var selectedEventId by remember { mutableStateOf<String?>(null) }
```

Change the existing `AnimatedContent(targetState = selectedExhibition, …)` block to nest a second decision: when an event is selected and no exhibition is selected, render `EventDetailScreen`. The simplest structure is to wrap the existing AnimatedContent in an outer when:

Replace the current block:

```kotlin
        AnimatedContent(
            targetState = selectedExhibition,
            transitionSpec = { fadeIn(animationSpec = androidx.compose.animation.core.tween(200)) togetherWith fadeOut(animationSpec = androidx.compose.animation.core.tween(200)) },
            label = "detailTransition",
        ) { exhibition ->
            if (exhibition != null) {
                PlatformBackHandler { selectedExhibition = null }
                ExhibitionDetailScreen(
                    exhibition = exhibition,
                    // ...
                )
            } else {
                Scaffold( ... ) { ... }
            }
        }
```

with:

```kotlin
        AnimatedContent(
            targetState = Triple(selectedExhibition, selectedEventId, /* tab */ selectedTab),
            transitionSpec = { fadeIn(animationSpec = androidx.compose.animation.core.tween(200)) togetherWith fadeOut(animationSpec = androidx.compose.animation.core.tween(200)) },
            label = "detailTransition",
        ) { (exhibition, eventId, _) ->
            when {
                exhibition != null -> {
                    PlatformBackHandler { selectedExhibition = null }
                    ExhibitionDetailScreen(
                        exhibition = exhibition,
                        lang = lang,
                        isBookmarked = exhibition.id in bookmarkedIds,
                        onBookmarkToggle = { viewModel.toggleBookmark(exhibition.id) },
                        onBack = { selectedExhibition = null },
                        thoughtRepository = thoughtRepository,
                        authState = authState,
                        isAdmin = isAdmin,
                        supabaseClient = supabaseClient,
                    )
                }
                eventId != null -> {
                    PlatformBackHandler { selectedEventId = null }
                    val eventDetailVm: EventDetailViewModel = viewModel(
                        key = "event-$eventId",
                        factory = EventDetailViewModel.factory(eventId, eventRepository),
                    )
                    EventDetailScreen(
                        viewModel = eventDetailVm,
                        lang = lang,
                        onBack = { selectedEventId = null },
                        onExhibitionTap = { selectedExhibition = it },
                    )
                }
                else -> {
                    Scaffold(
                        // ... existing topBar / bottomBar / content as before, unchanged ...
                    )
                }
            }
        }
```

Update the `FeaturedScreen(...)` call inside the `Scaffold` content to wire `onEventTap`:

```kotlin
                            0 -> FeaturedScreen(
                                viewModel = viewModel,
                                onExhibitionTap = { selectedExhibition = it },
                                onEventTap = { id -> selectedEventId = id },
                                modifier = Modifier.padding(innerPadding),
                            )
```

- [ ] **Step 4: Build the project**

```bash
./gradlew :composeApp:assembleDebug
```

Expected: BUILD SUCCESSFUL. (If iOS builds are part of the verification flow, also run the iOS framework task; otherwise leave that for the manual smoke test.)

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/EventDetailViewModel.kt \
        composeApp/src/commonMain/kotlin/com/gallr/app/ui/event/EventDetailScreen.kt \
        composeApp/src/commonMain/kotlin/com/gallr/app/App.kt
git commit -m "feat(event): add Event Detail screen with venues, exhibitions, and back nav"
```

---

### Task 11: Update SyncExhibitions.gs to recognize event_id

**Files:**
- Modify: `gas/SyncExhibitions.gs`

This script doesn't have automated tests in this repo — verification is via the Apps Script execution log against a test sheet.

- [ ] **Step 1: Add `event_id` to `KNOWN_COLUMNS`**

Find the `KNOWN_COLUMNS` array in `gas/SyncExhibitions.gs` and add `'event_id'` at the end of the non-bilingual section:

Replace:

```javascript
var KNOWN_COLUMNS = [
  // Bilingual text fields
  'name_ko', 'name_en',
  'venue_name_ko', 'venue_name_en',
  'city_ko', 'city_en',
  'region_ko', 'region_en',
  'description_ko', 'description_en',
  'address_ko', 'address_en',
  // Non-bilingual fields
  'opening_date', 'closing_date',
  'is_featured', 'is_editors_pick',
  'latitude', 'longitude',
  'cover_image_url',
  'hours',
  'contact',
  'reception_date',
  'opening_time',
];
```

with:

```javascript
var KNOWN_COLUMNS = [
  // Bilingual text fields
  'name_ko', 'name_en',
  'venue_name_ko', 'venue_name_en',
  'city_ko', 'city_en',
  'region_ko', 'region_en',
  'description_ko', 'description_en',
  'address_ko', 'address_en',
  // Non-bilingual fields
  'opening_date', 'closing_date',
  'is_featured', 'is_editors_pick',
  'latitude', 'longitude',
  'cover_image_url',
  'hours',
  'contact',
  'reception_date',
  'opening_time',
  'event_id',
];
```

- [ ] **Step 2: Add helper to fetch known event ids**

Add this function above the `// Main entry point` comment (just below the `REQUIRED_ROW_FIELDS` declaration):

```javascript
// ---------------------------------------------------------------------------
// Event id validation — fetches all event ids once per sync run.
// Returns a Set-like object: knownIds[id] === true when the id exists.
// ---------------------------------------------------------------------------

function fetchKnownEventIds(supabaseUrl, serviceKey) {
  var url = supabaseUrl + '/rest/v1/events?select=id';
  var response = UrlFetchApp.fetch(url, {
    method: 'get',
    headers: {
      'apikey': serviceKey,
      'Authorization': 'Bearer ' + serviceKey,
    },
    muteHttpExceptions: true,
  });
  var code = response.getResponseCode();
  if (code !== 200) {
    Logger.log('WARN: events fetch returned ' + code + ' — event_id validation disabled this run');
    return null; // null signals "validation disabled" so we don't accidentally skip every row
  }
  var rows = JSON.parse(response.getContentText());
  var set = {};
  rows.forEach(function(r) { if (r && r.id) set[r.id] = true; });
  return set;
}
```

- [ ] **Step 3: Wire validation into the row processing loop**

In `syncToSupabase()`, between the "Build header map" block and the "Process data rows" block (right before `var dataRows = data.slice(1);`), add:

```javascript
  // ── Fetch known event ids for FK validation ──────────────────────────
  var knownEventIds = fetchKnownEventIds(supabaseUrl, serviceKey);
```

Then in the `dataRows.forEach(function(row, index) { ... })` block, modify the per-row logic to also check `event_id`. Replace:

```javascript
  dataRows.forEach(function(row, index) {
    var rowNum = index + 2;
    var result = validateRow(row, rowNum, headerMap);
    if (result.valid) {
      validRows.push(buildRecord(row, headerMap));
    } else {
      skippedReasons.push(result.reason);
    }
  });
```

with:

```javascript
  dataRows.forEach(function(row, index) {
    var rowNum = index + 2;
    var result = validateRow(row, rowNum, headerMap);
    if (!result.valid) {
      skippedReasons.push(result.reason);
      return;
    }
    // Validate event_id FK if a value is present and validation is enabled
    var eventIdCell = String(getCell(row, headerMap, 'event_id') || '').trim();
    if (eventIdCell && knownEventIds !== null && !knownEventIds[eventIdCell]) {
      skippedReasons.push('Row ' + rowNum + ': event_id "' + eventIdCell + '" not found in events table — sync events first');
      return;
    }
    validRows.push(buildRecord(row, headerMap));
  });
```

- [ ] **Step 4: Verify by running the script against a test sheet**

This is a manual step:
1. Open the Apps Script editor for the existing `SyncExhibitions` project
2. Paste the updated script contents
3. Run `syncToSupabase` from the editor
4. Open Executions panel and confirm the JSON log shows `status: SUCCESS` and that any rows referencing a missing `event_id` appear in `skipped_details` with the new message

- [ ] **Step 5: Commit**

```bash
git add gas/SyncExhibitions.gs
git commit -m "feat(gas): validate exhibition event_id against events table during sync"
```

---

### Task 12: Create SyncEvents.gs

**Files:**
- Create: `gas/SyncEvents.gs`

This is a fresh GAS script for the operator's events spreadsheet. Modeled directly on `SyncExhibitions.gs`.

- [ ] **Step 1: Write `SyncEvents.gs`**

Create `gas/SyncEvents.gs`:

```javascript
/**
 * SyncEvents.gs
 * Google Apps Script — Sync Google Sheet → Supabase events table
 *
 * SETUP REQUIRED (one-time):
 * 1. Create a new Apps Script project bound to the events spreadsheet
 *    (separate from the exhibitions spreadsheet)
 * 2. Open Project Settings → Script Properties and add:
 *      SUPABASE_URL              = https://<project-ref>.supabase.co
 *      SUPABASE_SERVICE_ROLE_KEY = <your-service-role-key>  ← never share
 * 3. Install triggers (Triggers menu in Apps Script editor):
 *    a) onEdit: Function = syncEventsToSupabase, From spreadsheet, On edit
 *    b) Time-driven: Function = syncEventsToSupabase, Minutes timer, every 5 min
 *
 * GOOGLE SHEET LAYOUT:
 *   Row 1 = headers (lowercase snake_case matching Supabase column names)
 *   Data rows from row 2.
 *   Required headers: id, name_ko, name_en, location_label_ko,
 *                     location_label_en, start_date, end_date, brand_color
 *   Optional headers: description_ko, description_en, accent_color,
 *                     ticket_url, is_active
 */

var REQUIRED_HEADERS = [
  'id', 'name_ko', 'name_en',
  'location_label_ko', 'location_label_en',
  'start_date', 'end_date',
  'brand_color',
];

var REQUIRED_ROW_FIELDS = REQUIRED_HEADERS;

var KNOWN_COLUMNS = [
  'id',
  'name_ko', 'name_en',
  'description_ko', 'description_en',
  'location_label_ko', 'location_label_en',
  'start_date', 'end_date',
  'brand_color', 'accent_color',
  'ticket_url',
  'is_active',
];

function syncEventsToSupabase() {
  var props = PropertiesService.getScriptProperties();
  var supabaseUrl = props.getProperty('SUPABASE_URL');
  var serviceKey = props.getProperty('SUPABASE_SERVICE_ROLE_KEY');
  var timestamp = new Date().toISOString();

  if (!supabaseUrl || !serviceKey) {
    logFailure(timestamp, 'SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY not set in Script Properties');
    return;
  }

  var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheets()[0];
  var data = sheet.getDataRange().getValues();

  if (data.length === 0) {
    logFailure(timestamp, 'Sheet is empty — no header row found');
    return;
  }

  var headerRow = data[0];
  var headerMap = buildHeaderMap(headerRow);
  Logger.log('Headers found: ' + Object.keys(headerMap).join(', '));

  var missingHeaders = [];
  REQUIRED_HEADERS.forEach(function(h) { if (!(h in headerMap)) missingHeaders.push(h); });
  if (missingHeaders.length > 0) {
    logFailure(timestamp, 'Missing required headers: ' + missingHeaders.join(', '));
    return;
  }

  var dataRows = data.slice(1);
  var rowsRead = dataRows.length;
  var validRows = [];
  var skippedReasons = [];

  dataRows.forEach(function(row, index) {
    var rowNum = index + 2;
    var result = validateRow(row, rowNum, headerMap);
    if (result.valid) {
      validRows.push(buildRecord(row, headerMap));
    } else {
      skippedReasons.push(result.reason);
    }
  });

  // ── Deduplicate by id ─────────────────────────────────────────────────
  var seenIds = {};
  var uniqueRows = [];
  validRows.forEach(function(row) {
    if (!seenIds[row.id]) {
      seenIds[row.id] = true;
      uniqueRows.push(row);
    } else {
      skippedReasons.push('Duplicate id ' + row.id + ': ' + row.name_ko);
    }
  });

  if (uniqueRows.length === 0) {
    Logger.log(JSON.stringify({
      timestamp: timestamp,
      status: 'SKIPPED',
      error: 'No valid rows to insert — DELETE skipped to protect existing data',
      rows_read: rowsRead,
      rows_inserted: 0,
      rows_skipped: skippedReasons.length,
      skipped_details: skippedReasons,
    }));
    return;
  }

  try {
    deleteAllEvents(supabaseUrl, serviceKey);
    insertEvents(uniqueRows, supabaseUrl, serviceKey);
    Logger.log(JSON.stringify({
      timestamp: timestamp,
      status: 'SUCCESS',
      rows_read: rowsRead,
      rows_inserted: uniqueRows.length,
      rows_skipped: skippedReasons.length,
      skipped_details: skippedReasons,
    }));
  } catch (e) {
    Logger.log(JSON.stringify({
      timestamp: timestamp,
      status: 'FAILURE',
      error: e.message,
      rows_read: rowsRead,
      rows_inserted: 0,
      rows_skipped: skippedReasons.length,
    }));
  }
}

function buildHeaderMap(headerRow) {
  var map = {};
  headerRow.forEach(function(cell, index) {
    var header = String(cell || '').toLowerCase().trim();
    if (header) map[header] = index;
  });
  return map;
}

function getCell(row, headerMap, headerName) {
  if (!(headerName in headerMap)) return '';
  return row[headerMap[headerName]];
}

function validateRow(row, rowNum, headerMap) {
  for (var i = 0; i < REQUIRED_ROW_FIELDS.length; i++) {
    var field = REQUIRED_ROW_FIELDS[i];
    var value = String(getCell(row, headerMap, field) || '').trim();
    if (!value) {
      return { valid: false, reason: 'Row ' + rowNum + ': ' + field + ' is empty' };
    }
  }
  if (!parseDate(getCell(row, headerMap, 'start_date'))) {
    return { valid: false, reason: 'Row ' + rowNum + ': start_date is not a valid date' };
  }
  if (!parseDate(getCell(row, headerMap, 'end_date'))) {
    return { valid: false, reason: 'Row ' + rowNum + ': end_date is not a valid date' };
  }
  if (!isHexColor(getCell(row, headerMap, 'brand_color'))) {
    return { valid: false, reason: 'Row ' + rowNum + ': brand_color is not a valid hex (#RRGGBB)' };
  }
  var accent = String(getCell(row, headerMap, 'accent_color') || '').trim();
  if (accent && !isHexColor(accent)) {
    return { valid: false, reason: 'Row ' + rowNum + ': accent_color is not a valid hex (#RRGGBB)' };
  }
  return { valid: true };
}

function isHexColor(v) {
  var s = String(v || '').trim();
  return /^#?[0-9A-Fa-f]{6}$/.test(s);
}

function buildRecord(row, headerMap) {
  var record = {};
  KNOWN_COLUMNS.forEach(function(col) {
    if (!(col in headerMap)) return;
    var raw = getCell(row, headerMap, col);
    if (raw === '' || raw === null || raw === undefined) return;

    if (col === 'start_date' || col === 'end_date') {
      record[col] = parseDate(raw);
    } else if (col === 'is_active') {
      record[col] = (String(raw).toLowerCase() === 'true' || raw === true);
    } else if (col === 'brand_color' || col === 'accent_color') {
      var s = String(raw).trim();
      record[col] = (s.charAt(0) === '#') ? s : ('#' + s);
    } else {
      record[col] = String(raw).trim();
    }
  });
  return record;
}

function parseDate(v) {
  if (!v) return null;
  if (v instanceof Date) {
    var y = v.getFullYear();
    var m = String(v.getMonth() + 1).padStart(2, '0');
    var d = String(v.getDate()).padStart(2, '0');
    return y + '-' + m + '-' + d;
  }
  var str = String(v).trim();
  if (/^\d{4}-\d{2}-\d{2}$/.test(str)) return str;
  var parsed = new Date(str);
  if (isNaN(parsed.getTime())) return null;
  var y2 = parsed.getFullYear();
  var m2 = String(parsed.getMonth() + 1).padStart(2, '0');
  var d2 = String(parsed.getDate()).padStart(2, '0');
  return y2 + '-' + m2 + '-' + d2;
}

function deleteAllEvents(supabaseUrl, serviceKey) {
  var url = supabaseUrl + '/rest/v1/events?id=neq.__never__';
  var response = UrlFetchApp.fetch(url, {
    method: 'delete',
    headers: {
      'apikey': serviceKey,
      'Authorization': 'Bearer ' + serviceKey,
      'Prefer': 'return=minimal',
    },
    muteHttpExceptions: true,
  });
  var code = response.getResponseCode();
  if (code !== 204 && code !== 200) {
    throw new Error('Delete events failed with code ' + code + ': ' + response.getContentText());
  }
}

function insertEvents(rows, supabaseUrl, serviceKey) {
  var url = supabaseUrl + '/rest/v1/events';
  var response = UrlFetchApp.fetch(url, {
    method: 'post',
    contentType: 'application/json',
    headers: {
      'apikey': serviceKey,
      'Authorization': 'Bearer ' + serviceKey,
      'Prefer': 'return=minimal',
    },
    payload: JSON.stringify(rows),
    muteHttpExceptions: true,
  });
  var code = response.getResponseCode();
  if (code !== 201 && code !== 200) {
    throw new Error('Insert events failed with code ' + code + ': ' + response.getContentText());
  }
}

function logFailure(timestamp, message) {
  Logger.log(JSON.stringify({
    timestamp: timestamp,
    status: 'FAILURE',
    error: message,
    rows_read: 0,
    rows_inserted: 0,
    rows_skipped: 0,
  }));
}
```

- [ ] **Step 2: Verify (manual)**

1. Create a new Apps Script project bound to the events spreadsheet
2. Paste the script contents
3. Set the script properties (`SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`)
4. Add the row for Loop Lab Busan to the events sheet (id, name_ko/en, location labels, dates, brand_color = `#0099FF`, accent_color = `#FF5C5C`)
5. Run `syncEventsToSupabase` manually
6. Confirm the events row appears in the Supabase `events` table

- [ ] **Step 3: Commit**

```bash
git add gas/SyncEvents.gs
git commit -m "feat(gas): add SyncEvents.gs for syncing events sheet to Supabase"
```

---

### Task 13: End-to-end smoke test (manual)

This task is a sign-off step — no code changes, no commit. Run through it before declaring Phase 1 done.

- [ ] **Step 1: Confirm pipeline**

Verify (via Supabase dashboard):
- The `events` table contains exactly one row for Loop Lab Busan 2025
- 2-3 exhibition rows have `event_id = "loop-lab-busan-2025"` (or whatever the operator chose as the id)

- [ ] **Step 2: Run the app on iOS simulator and Android emulator**

```bash
./gradlew :composeApp:installDebug   # Android
# Open iosApp in Xcode, run on simulator
```

- [ ] **Step 3: Walk through the user flows**

For each platform:

1. Open Featured tab → see the blue Loop Lab Busan promoted card above the FEATURED heading
2. Tap card → see Event Detail page with branded blue header, "BUSAN" in coral, About section, Participating Venues chips, Exhibitions list
3. Tap an exhibition row → see standard exhibition detail
4. Back → return to Event Detail
5. Back → return to Featured tab (card still present)
6. Toggle language (Settings menu → Language) → all event text updates in place
7. In Supabase, set `is_active = false` on the event row → wait for next sync → reopen Featured → card is gone
8. Reset `is_active = true` → card returns

- [ ] **Step 4: Walk through the no-event state**

Set the event's `end_date` to yesterday (or `is_active = false`). Verify:
- Featured tab looks identical to pre-Phase-1
- Event Detail unreachable from any UI surface

---

## Self-Review

**Spec coverage check:**
- §5 (Data Model) → Task 1 (migration), Task 5 (eventId on Exhibition)
- §6 (Data Pipeline) → Task 11 (SyncExhibitions update), Task 12 (SyncEvents)
- §7 (KMP Domain Layer) → Task 3 (Event), Task 4 (EventDto), Task 6 (EventApiClient), Task 7 (EventRepository)
- §8.1 (Featured promoted card) → Task 9
- §8.2 (Event Detail screen) → Task 10
- §9 (Navigation) → Task 10 (state-based, deviation from spec's nav-graph language is documented in Architecture above)
- §10 (State Management) → Task 8 (TabsViewModel.activeEvent), Task 10 (EventDetailViewModel)
- §11 (Edge cases) → Task 7 tests cover active-window edges; Tasks 9 & 10 handle malformed brand_color via `parseHexColor` returning null + Color.Black fallback; Task 11 implements skip-and-log for missing event_id
- §12 (Testing) → Tasks 2, 3, 4, 7 implement the unit tests; Task 13 is the manual smoke
- §13 (Rollout) → Tasks land in execution order matching the rollout sequence (migration → GAS → app)

No spec section is missing a task.

**Placeholders:** Task 9 step 3 leaves `onEventTap = { /* wired in Task 10 */ }` — this is intentional (a temporary stub the next task replaces) and is not a "fill in details" placeholder.

**Type consistency:**
- `Event.nameLastToken` is a static `companion object` function called from both `EventPromotionCard.kt` and `EventDetailScreen.kt` — same signature.
- `EventApi` interface used by `EventApiClient` and the test fake — matched.
- `EventRepository.getActiveEvents()` returns `Result<List<Event>>` everywhere it's called.
- `EventDetailViewModel.factory(eventId, eventRepository)` matches its construction in `App.kt`.
- `FeaturedScreen(viewModel, onExhibitionTap, onEventTap, modifier)` — Task 9 adds the parameter, Task 10 changes the call site, no Task 9 caller goes unupdated because Task 9 step 3 also fixes `App.kt`.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-22-city-wide-event-phase1.md`. Two execution options:

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks, fast iteration
2. **Inline Execution** — execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
