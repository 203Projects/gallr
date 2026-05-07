# Quickstart: Exhibition Card Image Background

**Feature**: 019-card-image-background
**Date**: 2026-03-26

## What This Feature Does

Adds the exhibition's installation view image as a full-bleed background to exhibition cards in the Featured and List tabs. A semi-transparent scrim overlay ensures text remains readable. Cards without images fall back to a subtle `surfaceVariant` solid background.

## Files Changed

| File | Change Type | Description |
|------|------------|-------------|
| `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/ExhibitionCard.kt` | Modified | Replace `Surface` with `Box`; add `AsyncImage` + scrim overlay; branch color logic for image vs non-image cards |

## No New Dependencies

- `coverImageUrl: String?` already exists on `Exhibition` model
- `coil3.compose.AsyncImage` already available (Coil 3.1.0 in `build.gradle.kts`)
- No new files, modules, or packages

## Key Design Decisions

1. **Surface → Box**: `Surface` can't host a background image; `Box` with `Modifier.border()` preserves the 1dp card border
2. **Scrim overlay**: Flat color overlay (not gradient) with animated alpha for press feedback
3. **Color branching**: Image cards use fixed white/black text colors with specific alpha values; non-image cards preserve existing animated invert behavior
4. **Silent fallback**: Failed image loads treated identically to null URLs — no error UI

## How to Verify

1. **Image cards**: Open Featured tab; cards with `coverImageUrl` show the image as background with scrim
2. **No-image cards**: Cards with null `coverImageUrl` show `surfaceVariant` background
3. **Dark/light mode**: Toggle theme; verify scrim and text colors match spec
4. **Press state**: Long-press an image card; scrim darkens (no color inversion)
5. **Both tabs**: Verify identical behavior on Featured and List tabs
