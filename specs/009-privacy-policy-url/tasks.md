# Tasks: Add Privacy Policy URL

**Input**: Design documents from `/specs/009-privacy-policy-url/`
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, quickstart.md ✅

**Tests**: Not requested — UI-only change (tappable link) + static HTML page. Exempt per Constitution Principle II. Manual acceptance via quickstart.md.

**Organization**: Two user stories, two files. US1 (in-app link) and US2 (web page) are fully independent — either can be implemented and tested without the other.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: User story label (US1, US2)

## Path Conventions

```text
composeApp/src/commonMain/kotlin/com/gallr/app/App.kt  ← US1: add info IconButton
web/privacy.html                                        ← US2: new static privacy page
specs/009-privacy-policy-url/quickstart.md             ← acceptance checklist
```

---

## Phase 1: Setup

**Purpose**: Confirm existing code structure before making changes.

- [X] T001 Read `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt` — confirm: (a) existing `TopAppBar` has no `actions` slot yet, (b) which Material3 imports are already present, (c) no existing `LocalUriHandler` usage

---

## Phase 2: User Story 1 — In-App Privacy Policy Link (Priority: P1) 🎯 MVP

**Goal**: Users can tap an info icon in the top-right of the app bar from any tab and be taken to `https://gallrmap.com/privacy` in the device browser.

**Independent Test**: Build and run the app → tap the ⓘ icon in the TopAppBar → confirm the device browser opens `https://gallrmap.com/privacy`.

### Implementation for User Story 1

- [X] T002 [US1] In `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`: add `private const val PRIVACY_POLICY_URL = "https://gallrmap.com/privacy"` at the top of the file (above the `App` composable), add `import androidx.compose.ui.platform.LocalUriHandler`, `import androidx.compose.material3.Icon`, `import androidx.compose.material3.IconButton`, `import androidx.compose.material.icons.Icons`, `import androidx.compose.material.icons.outlined.Info` to the imports, then add an `actions` lambda to the existing `TopAppBar` that reads `val uriHandler = LocalUriHandler.current` and calls `uriHandler.openUri(PRIVACY_POLICY_URL)` when an `IconButton` with `Icons.Outlined.Info` is tapped (contentDescription = "Privacy Policy")
- [X] T003 [US1] Build and install app on Android emulator (`./gradlew :composeApp:installDebug && adb shell am start -n com.gallr.app/.MainActivity`), navigate to each of the three tabs (FEATURED, LIST, MAP), tap the info icon from each tab — confirm the device browser opens `https://gallrmap.com/privacy` each time (SC-002, SC-003)

**Checkpoint**: Info icon visible in TopAppBar on all tabs; tapping opens `https://gallrmap.com/privacy`. US1 complete and independently verified.

---

## Phase 3: User Story 2 — Privacy Policy Page on gallrmap.com (Priority: P2)

**Goal**: `https://gallrmap.com/privacy` returns HTTP 200 and displays privacy policy content using the existing gallrmap.com site design.

**Independent Test**: Navigate to `https://gallrmap.com/privacy` in a browser — confirm the page loads with the gallr header, footer, and privacy policy content.

### Implementation for User Story 2

- [X] T004 [US2] Create `web/privacy.html` as a new Eleventy page with front matter `layout: base.html` and a `<section class="privacy"><div class="privacy__inner">` containing: an `<h1>Privacy Policy</h1>`, a "Last updated: 2026-03-20" paragraph, and placeholder body text indicating that the full privacy policy text will be provided by the product owner (include gallr contact email placeholder). The page must use the existing `base.html` layout (which provides site header, footer, and stylesheets) and must be readable on mobile without horizontal scrolling (no fixed-width elements)
- [X] T005 [US2] Verify the Eleventy build includes the new privacy page: run `cd web && npm run build` from repo root and confirm `web/dist/privacy/index.html` (or `web/dist/privacy.html`) exists with the correct content

**Checkpoint**: `web/dist/privacy/index.html` built successfully. When deployed to gallrmap.com, `https://gallrmap.com/privacy` will serve this page. US2 complete.

---

## Phase 4: Polish & Cross-Cutting Concerns

**Purpose**: Full acceptance verification and commit.

- [X] T006 [P] Run quickstart.md acceptance checklist (`specs/009-privacy-policy-url/quickstart.md`): verify SC-001 (page accessible), SC-002 (reachable in 1 tap), SC-003 (works from all tabs), SC-004 (no crash offline) — document pass/fail for each criterion
- [x] T007 Commit all changes on branch `009-privacy-policy-url`: completed by commit `2e8bbe5`.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **US1 (Phase 2)**: Depends on Phase 1 (T001) — confirms TopAppBar structure before editing
- **US2 (Phase 3)**: Independent of Phase 1 and US1 — can start any time
- **Polish (Phase 4)**: Depends on US1 (T002, T003) and US2 (T004, T005) complete

### User Story Dependencies

- **US1 (P1)**: Depends on T001. No dependency on US2.
- **US2 (P2)**: No dependencies. Fully independent.

### Parallel Opportunities

- T004 and T005 (US2 web page) can run in parallel with T002 and T003 (US1 in-app link) — different files, no shared state

---

## Parallel Example: US1 and US2

```bash
# Both stories work on different files — can proceed simultaneously:
# US1: edit composeApp/src/commonMain/kotlin/com/gallr/app/App.kt
# US2: create web/privacy.html
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Read App.kt (T001)
2. Complete Phase 2: Add info icon to TopAppBar (T002), verify on emulator (T003)
3. **STOP and VALIDATE**: Info icon opens `https://gallrmap.com/privacy` — US1 complete
4. US1 alone unblocks app store metadata update (FR-004) without waiting for the web page

### Incremental Delivery

1. Phase 1 → App.kt confirmed
2. US1 → In-app link functional → MVP ✅ (store metadata can be updated)
3. US2 → Web page built and deployed → `https://gallrmap.com/privacy` live
4. Polish → Acceptance checklist passed, committed

### Single Developer Strategy

Work sequentially: T001 → T002 → T003 (US1 complete) → T004 → T005 (US2 complete) → T006 → T007.

---

## Notes

- [P] tasks = different files, no shared state
- No unit tests — UI-only composable change + static HTML page, exempt per Constitution Principle II
- US2 web page content is a placeholder — legal text to be provided by product owner before App Store submission
- App store metadata update (FR-004) is a manual step documented in quickstart.md, not a code task
- Commit (T007) is the final task — do not commit until acceptance checklist (T006) passes
