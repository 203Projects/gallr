# Specification: Deterministic homepage visual regression

## Goal

Protect the public homepage hero and curated exhibition grid from unintended
layout regressions without depending on live catalogue data, remote artwork, or
timer-driven animation.

## Acceptance criteria

1. Desktop hero, mobile hero, and desktop curated-grid screenshots have
   committed Playwright baselines.
2. Tests use the existing deterministic build date and committed showcase seed.
3. Remote exhibition images are replaced in-browser with deterministic test
   pixels before navigation; no screenshot depends on network media.
4. Reduced motion, fixed viewports, loaded fonts, hidden carets, and disabled
   animations make captures repeatable.
5. Baselines preserve the current `DESIGN.md` hierarchy and fail when material
   spacing, typography, borders, or composition changes.

## Out of scope

- Freezing real exhibition artwork in the repository.
- Replacing existing semantic, accessibility, or responsive assertions.
- Automatically accepting changed screenshots in CI.
