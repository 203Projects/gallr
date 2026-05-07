# Default Language Korean + Profile Photo UX — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Change the default app language to Korean on first launch (regardless of device locale) and fix profile photo UX by removing the camera emoji and making the text button the sole photo-picker trigger.

**Architecture:** Two independent features touching separate code paths. Feature 1 modifies the language repository and its platform constructors. Feature 2 modifies `EditProfileScreen` composable only. Both use TDD — write failing tests first, then implement.

**Tech Stack:** Kotlin 2.1.20 (KMP), Compose Multiplatform 1.8.0, DataStore Preferences, kotlin.test + kotlinx.coroutines.test

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Modify | `shared/src/commonMain/kotlin/com/gallr/shared/repository/LanguageRepositoryImpl.kt` | Remove `systemLanguage` lambda, hardcode `AppLanguage.KO` fallback |
| Modify | `composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt` | Remove locale-detection lambda from constructor |
| Modify | `composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt` | Remove locale-detection lambda from constructor |
| Create | `shared/src/commonTest/kotlin/com/gallr/shared/repository/LanguageRepositoryTest.kt` | Unit tests for language default behavior |
| Modify | `composeApp/src/commonMain/kotlin/com/gallr/app/ui/profile/EditProfileScreen.kt` | Remove emoji, remove clickable from avatar, convert text to button |

---

### Task 1: Write failing tests for LanguageRepository default behavior

**Files:**
- Create: `shared/src/commonTest/kotlin/com/gallr/shared/repository/LanguageRepositoryTest.kt`

- [ ] **Step 1: Create the test file**

```kotlin
package com.gallr.shared.repository

import com.gallr.shared.data.model.AppLanguage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageRepositoryTest {

    @Test
    fun `observeLanguage returns Korean when no preference is saved`() = runTest {
        val repo = FakeLanguageRepository(savedLanguage = null)
        assertEquals(AppLanguage.KO, repo.observeLanguage().first())
    }

    @Test
    fun `observeLanguage returns English when English preference is saved`() = runTest {
        val repo = FakeLanguageRepository(savedLanguage = AppLanguage.EN)
        assertEquals(AppLanguage.EN, repo.observeLanguage().first())
    }

    @Test
    fun `observeLanguage returns Korean when Korean preference is saved`() = runTest {
        val repo = FakeLanguageRepository(savedLanguage = AppLanguage.KO)
        assertEquals(AppLanguage.KO, repo.observeLanguage().first())
    }

    @Test
    fun `setLanguage persists and is observable`() = runTest {
        val repo = FakeLanguageRepository(savedLanguage = null)
        repo.setLanguage(AppLanguage.EN)
        assertEquals(AppLanguage.EN, repo.observeLanguage().first())
    }
}

/**
 * In-memory fake that mirrors the contract of [LanguageRepositoryImpl]:
 * - When [savedLanguage] is null, the default is [AppLanguage.KO].
 */
private class FakeLanguageRepository(
    savedLanguage: AppLanguage?,
) : LanguageRepository {

    private var current: AppLanguage = savedLanguage ?: AppLanguage.KO

    override fun observeLanguage() = kotlinx.coroutines.flow.MutableStateFlow(current)

    override suspend fun setLanguage(language: AppLanguage) {
        current = language
    }
}
```

- [ ] **Step 2: Run tests to verify they pass (baseline — these test the contract, not the impl)**

Run: `./gradlew :shared:cleanAllTests :shared:allTests --tests "com.gallr.shared.repository.LanguageRepositoryTest" --no-build-cache 2>&1 | tail -20`

Expected: All 4 tests PASS. The fake already encodes the Korean-default contract. The real value of these tests is to validate the contract we expect, which we'll verify against the real impl after Task 2.

- [ ] **Step 3: Commit**

```bash
git add shared/src/commonTest/kotlin/com/gallr/shared/repository/LanguageRepositoryTest.kt
git commit -m "test: add LanguageRepository contract tests with Korean default"
```

---

### Task 2: Implement default Korean in LanguageRepositoryImpl

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gallr/shared/repository/LanguageRepositoryImpl.kt`

- [ ] **Step 1: Remove `systemLanguage` parameter and hardcode `AppLanguage.KO` fallback**

Replace the entire file content with:

```kotlin
package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gallr.shared.data.model.AppLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val LANGUAGE_KEY = stringPreferencesKey("app_language")

class LanguageRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
) : LanguageRepository {

    override fun observeLanguage(): Flow<AppLanguage> =
        dataStore.data.map { prefs ->
            when (prefs[LANGUAGE_KEY]) {
                "ko" -> AppLanguage.KO
                "en" -> AppLanguage.EN
                else -> AppLanguage.KO
            }
        }

    override suspend fun setLanguage(language: AppLanguage) {
        dataStore.edit { prefs ->
            prefs[LANGUAGE_KEY] = when (language) {
                AppLanguage.KO -> "ko"
                AppLanguage.EN -> "en"
            }
        }
    }
}
```

Key changes:
- Constructor: removed `private val systemLanguage: () -> AppLanguage` parameter
- Line 24 (`else` branch): changed from `systemLanguage()` to `AppLanguage.KO`

- [ ] **Step 2: Verify contract tests still pass**

Run: `./gradlew :shared:cleanAllTests :shared:allTests --tests "com.gallr.shared.repository.LanguageRepositoryTest" --no-build-cache 2>&1 | tail -20`

Expected: All 4 tests PASS.

- [ ] **Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gallr/shared/repository/LanguageRepositoryImpl.kt
git commit -m "feat: default app language to Korean on first launch"
```

---

### Task 3: Update platform constructors (Android + iOS)

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt:66-69`
- Modify: `composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt:50-53`

- [ ] **Step 1: Update Android constructor**

In `MainActivity.kt`, replace:

```kotlin
        val languageRepository = LanguageRepositoryImpl(dataStore) {
            val locale = java.util.Locale.getDefault().language
            if (locale == "ko") AppLanguage.KO else AppLanguage.EN
        }
```

With:

```kotlin
        val languageRepository = LanguageRepositoryImpl(dataStore)
```

- [ ] **Step 2: Remove unused Android import**

In `MainActivity.kt`, remove the import for `AppLanguage` if it is no longer referenced elsewhere in the file. Check first — it may be used by other code in the file.

- [ ] **Step 3: Update iOS constructor**

In `MainViewController.kt`, replace:

```kotlin
    val languageRepository = LanguageRepositoryImpl(dataStore) {
        val locale = NSLocale.currentLocale.languageCode
        if (locale == "ko") AppLanguage.KO else AppLanguage.EN
    }
```

With:

```kotlin
    val languageRepository = LanguageRepositoryImpl(dataStore)
```

- [ ] **Step 4: Remove unused iOS import**

In `MainViewController.kt`, remove the `NSLocale` import (`import platform.Foundation.NSLocale`) if it is no longer used elsewhere in the file. Also remove the `AppLanguage` import if unused.

- [ ] **Step 5: Verify project compiles**

Run: `./gradlew :composeApp:compileKotlinIosSimulatorArm64 :composeApp:compileDebugKotlin 2>&1 | tail -20`

Expected: BUILD SUCCESSFUL (no compilation errors).

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt
git commit -m "refactor: remove locale-detection lambdas from platform constructors"
```

---

### Task 4: Remove camera emoji from profile photo circle

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/profile/EditProfileScreen.kt:171-187`

- [ ] **Step 1: Delete the camera emoji overlay block**

In `EditProfileScreen.kt`, delete lines 171-187 (the entire camera icon overlay block):

```kotlin
            // Camera icon overlay
            if (!isUploadingAvatar) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onBackground),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "📷",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
```

- [ ] **Step 2: Verify project compiles**

Run: `./gradlew :composeApp:compileDebugKotlin 2>&1 | tail -20`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/ui/profile/EditProfileScreen.kt
git commit -m "fix: remove camera emoji from profile photo circle"
```

---

### Task 5: Make avatar circle display-only and text button the tap target

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/profile/EditProfileScreen.kt:125-198`

- [ ] **Step 1: Remove `.clickable` and action-related semantics from avatar Box**

In `EditProfileScreen.kt`, replace the avatar description and Box block (lines 125-136):

```kotlin
        val avatarDescription = when (lang) {
            AppLanguage.KO -> "프로필 사진 변경"
            AppLanguage.EN -> "Change profile photo"
        }
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = !isUploadingAvatar) { pickImage() }
                .semantics { contentDescription = avatarDescription },
            contentAlignment = Alignment.Center,
        ) {
```

With:

```kotlin
        val avatarDescription = when (lang) {
            AppLanguage.KO -> "프로필 사진"
            AppLanguage.EN -> "Profile photo"
        }
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics { contentDescription = avatarDescription },
            contentAlignment = Alignment.Center,
        ) {
```

Key changes:
- Removed `.clickable(enabled = !isUploadingAvatar) { pickImage() }` modifier
- Changed description from "Change profile photo" to "Profile photo" (it's now display-only, not an action)

- [ ] **Step 2: Convert passive Text to a TextButton that opens the photo picker**

Replace the change-photo Text block (after `Spacer(Modifier.height(8.dp))`):

```kotlin
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (lang) {
                AppLanguage.KO -> "사진 변경"
                AppLanguage.EN -> "Change Photo"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
```

With:

```kotlin
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (lang) {
                AppLanguage.KO -> "사진 변경"
                AppLanguage.EN -> "Change Photo"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(enabled = !isUploadingAvatar) { pickImage() },
        )
```

Key change: Added `Modifier.clickable(enabled = !isUploadingAvatar) { pickImage() }` to the Text.

- [ ] **Step 3: Remove unused imports if applicable**

Check if `offset` import (`import androidx.compose.foundation.layout.offset`) is still used elsewhere in the file. If not (the only usage was the camera emoji overlay offset), remove it.

- [ ] **Step 4: Verify project compiles**

Run: `./gradlew :composeApp:compileDebugKotlin 2>&1 | tail -20`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/ui/profile/EditProfileScreen.kt
git commit -m "fix: make text button sole photo-picker trigger, avatar display-only"
```

---

### Task 6: Run all tests and final verification

**Files:** None (verification only)

- [ ] **Step 1: Run all shared module tests**

Run: `./gradlew :shared:cleanAllTests :shared:allTests --no-build-cache 2>&1 | tail -30`

Expected: All tests PASS including the new `LanguageRepositoryTest`.

- [ ] **Step 2: Verify full project compiles for both platforms**

Run: `./gradlew :composeApp:compileDebugKotlin :composeApp:compileKotlinIosSimulatorArm64 2>&1 | tail -20`

Expected: BUILD SUCCESSFUL for both targets.

- [ ] **Step 3: Verify no remaining references to removed code**

Run these checks:
- `grep -r "systemLanguage" shared/src/commonMain/` — Expected: no results
- `grep -r "📷" composeApp/src/` — Expected: no results
- `grep -rn "NSLocale" composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt` — Expected: no results (unless used elsewhere)
