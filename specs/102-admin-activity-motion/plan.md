# Implementation plan

Use the published MIT-licensed thinking-orbs React package, pinned in Admin's lockfile. Add one shared activity indicator with inline and panel presentations, explicit light ink/background behavior, localized caller-provided labels, and optional live announcements for existing status regions. Integrate at existing asynchronous state boundaries. Use CSS opacity-only 180ms entrances and the existing reduced-motion override. Keep repository operations unchanged.

Validate with Admin typecheck, full Vitest suite, production build, and local fixture browser checks at desktop/mobile sizes, including reduced-motion rendering. Browser plugin is unavailable; use Playwright. No production credentials are needed.

## Constitution check

Pass: this is staff operator UI, outside the consumer Mobile-First constraint.
Changes stay in Admin presentation components and do not add shared business
logic, repository behavior, backend contracts, or platform-specific mobile code.
Existing tests cover workflow behavior; real-browser checks verify motion and
accessibility. No new architecture abstraction beyond the shared indicator.
