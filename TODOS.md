# TODOS

Last updated: 2026-08-10

## P1 — Post-Launch

### iOS Xcode Cloud Archive failing on every release promotion
The `iosApp | Default | Archive - iOS` Xcode Cloud step has failed on every
`develop → main` promotion for at least the last two releases (PR #63 v1.6.x and
PR #70 v1.6.2 — identical failure). Web ships fine via Vercel, and the Kotlin/iOS
code compiles clean (`compileKotlinIosSimulatorArm64` passes locally), so this is
an App Store packaging/signing/provisioning problem, not a code bug. **Net effect:
no new iOS App Store build has shipped across these releases.**
- Effort: M (human — needs App Store Connect / signing access) → S (CC: config only)
- Likely cause: `iosApp/ExportOptions-AppStore.plist` is **untracked** (not
  committed to the repo), so Xcode Cloud archives without correct export options /
  signing config. First step: commit a correct ExportOptions plist, verify
  provisioning profile + bundle ID + team in `iosApp.xcodeproj`, re-run the
  Xcode Cloud workflow. Pull the failing build log from App Store Connect
  (not visible via `gh` — it's the appstoreconnect.apple.com CI, build
  b5524d87 for #70).

### Push Notifications
Weekly "N new exhibitions near you" push via FCM (Android) + APNs (iOS). Primary retention mechanism. Needs server-side trigger (Cloud Function or GAS extension). Depends on basic analytics being in place.
- Effort: M (human) → S (CC: ~1 day)
- Context: Design doc identifies retention as key initiative. Without a trigger, users forget to open the app.

## P2 — Quality of Life

### Open in Maps
Button on ExhibitionDetailScreen to open Apple Maps / Naver Map / Google Maps with exhibition coordinates. Completes the discover → save → navigate → visit loop.
- Effort: S (CC: ~30 min)
- Context: Latitude/longitude already in data model but unused on detail screen.

### Featured/Editor's Pick Badges
Show visual badges on detail screen and cards for featured / editor's pick exhibitions.
- Effort: S (CC: ~30 min)
- Context: `isFeatured` and `isEditorsPick` fields exist in data model.

### Move Visited Exhibitions into Profile
Add a visited-exhibition history or collection section to the Profile tab. The Map tab should remain focused on discovery and bookmarks; visit history belongs with the user's identity and activity.
- Effort: M (CC: ~2 hours)
- Context: `PersonalMapMode.VISITED` and visited aggregate data already exist and can be reused once the Profile presentation and navigation are designed.

## P2 — Quality of Life (continued)

### DESIGN.md — Codify the Design System
Formalize gallr's design system (colors, typography, spacing, component patterns, accent usage rules) in a DESIGN.md file. Currently the design system lives only in Kotlin code (GallrColors.kt, GallrTypography.kt). Every new screen requires reading source code to understand visual rules. GallrAccent has explicit usage rules (only for CTA, active indicator, interaction feedback) in code comments but not in a design doc.
- Effort: S (CC: ~15 min)
- Context: No prerequisites. Can be done anytime. Run `/design-consultation` for a thorough approach, or extract directly from GallrColors.kt + GallrTypography.kt.

## P3 — Technical Debt

### ViewModel Splitting
Split TabsViewModel (15+ StateFlows) into domain-specific ViewModels (ExhibitionViewModel, FilterViewModel, MapViewModel). Cleaner separation, easier testing.
- Effort: M (human) → S (CC: ~2 hours)
- Context: Single VM is manageable now but approaching god-object threshold.

### Migrate ExhibitionApiClient to supabase-kt Postgrest
Replace the raw Ktor ExhibitionApiClient with supabase-kt's Postgrest module for exhibition fetching. Eliminates dual HTTP client tech debt (two Ktor instances = two connection pools, two configs). ExhibitionApiClient.kt is 49 lines doing `GET /exhibitions?select=*`. Equivalent supabase-kt: `supabase.from("exhibitions").select()`. Migration is ~30 lines.
- Effort: S (CC: ~15 min)
- Depends on: Social layer Phase 1 complete (supabase-kt already in project)
- Context: Two Ktor engines with potentially different versions cause subtle runtime bugs. The dual-client approach is accepted tech debt for the social layer launch but should be resolved in the next cleanup pass.

### FilterViewModel Extraction
Extract city/region filter state (distinctCities, distinctRegions, selectedCity, toggleRegion, clearRegions) from TabsViewModel into a dedicated FilterViewModel. TabsViewModel now has 17+ StateFlows after the city/region filter feature.
- Effort: S (CC: ~1 hour)
- Context: Natural extraction boundary. City/region filter logic is self-contained. Would also make the filter logic independently testable without mocking repositories.

### Proper Logging Framework
Replace println() calls with Napier or similar KMP logging library. Production crashes and errors are currently invisible.
- Effort: S (CC: ~1 hour)
- Context: TabsViewModel.kt:229,244 use println() for error logging.

### Full Analytics Dashboard
Expand basic 3-event logging to a proper analytics solution (Mixpanel, Amplitude, or Supabase dashboard).
- Effort: M (CC: ~1 day)
- Depends on: Basic analytics events being in place first.

### GAS Stale Record Cleanup
After switching to UPSERT, exhibitions deleted from the Google Sheet stay in Supabase. Add `last_synced_at` column and periodic cleanup of records not seen in recent syncs.
- Effort: S (CC: ~1 hour)
- Context: UPSERT fixes the destructive sync issue but introduces stale data risk.

### Story-Card Renderer Cleanup (deferred from PR #67 review)
The Android/iOS story-card renderers duplicate the layout contract (offsets, fonts, gaps) as divergent magic numbers; `ExhibitionStoryShareConfig` only holds frame/margins. Android `drawMultilineText` wraps on spaces, so space-less Korean titles (the primary language) overflow and hard-truncate via `.take(42)` with no ellipsis, while iOS uses `UILabel` wrapping — the two platforms render different cards for the same exhibition. Also: shared `HttpClient` is process-lifetime and never closed; no max-body/content-type cap on the cover download (trusted DB source today, but the GAS→Sheet sync is operator-editable); `cacheDir/share` PNGs are never pruned; image bytes are decoded full-res with no `inSampleSize` downsampling.
- Effort: M (CC: ~2 hours)
- Context: All pre-date the cover-image fix (original share commit). Extract a shared declarative card spec consumed by both renderers; move the wrap/truncate algorithm into testable commonMain (DI a `measureWidth` lambda like `CoverImageDownloader`'s `ByteFetcher`); add downsampling + a body-size cap; prune the share cache. Not blocking — cover-image PR shipped the correctness fixes (main-thread, scope, double-tap, id sanitize).
