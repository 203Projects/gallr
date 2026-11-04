# Tasks

- [x] Install and inspect Thinking Orbs; add shared indicator.
- [x] Integrate real waiting/working states and functional content fades.
- [x] Verify accessible labels, disabled controls, completion and error behavior.
- [x] Run Admin gates and desktop/mobile/reduced-motion browser checks.

## Verification

- Node 22.23.1: typecheck, 39 Vitest files / 630 tests, and production build pass.
  Test processes explicitly clear backend configuration to use isolated fixtures.
- Chromium / Playwright at 1440×1000 and 390×844: real fixture loading,
  autosave, save failure, archive confirmation/completion, Escape focus return,
  KO/EN labels, and no horizontal mobile overflow verified.
- Canvas frames change normally and remain identical with reduced motion;
  opacity entrances reduce to 0.01ms. Dark OS preference preserves light-surface ink.
- Busy button labels remain accessible (no nested status role); canvas is
  decorative to assistive technology. No browser errors or warnings observed.
- Vite reports the single bundle over 500kB warning. Live backend operations
  and Safari/Firefox were not exercised; no deployment was performed.

## Integration against develop

- Reapplied to develop `59486519`; preserved current editor-access controls and
  compact-height inspector styles. Renumbered the spec to 102 because 070 is occupied.
- Re-ran all Admin gates and fixture autosave at mobile/desktop sizes, including
  animated/static reduced-motion frame checks and completion.
