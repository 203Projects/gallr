# Plan: Deterministic homepage visual regression

Add one `homepage-visual.test.ts` file to the existing JavaScript-enabled
Chromium project. A shared setup fixes reduced motion and fulfills every remote
catalogue image with a committed-in-test one-pixel PNG before loading `/`.

Capture three bounded surfaces rather than one oversized page:

- desktop `.hero` at 1280×900;
- mobile `.hero` at 390×844;
- desktop `#now-showing` at 1280×900.

Use Playwright's platform-specific snapshot names because Chromium font metrics
change element height between macOS and Ubuntu. Keep a small within-platform
pixel tolerance for rasterization. The existing DOM, WCAG, mobile breakpoint,
reduced-motion, and interaction tests remain the behavioral gates.

## Verification

1. Observe missing-baseline failures before generating snapshots.
2. Generate baselines intentionally and inspect the rendered homepage.
3. Run the focused visual project twice without updating snapshots.
4. Run the complete public-web test suite under Node 22.23.1.
5. Require CI to compare, never update, committed snapshots.
