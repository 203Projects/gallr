# Implementation Plan: My Gallr contextual account conversion

- Add a tiny shared DataStore-backed dismissal repository with explicit decode behavior.
- Add eligibility and dismissal state to `MyGallrViewModel`.
- Render a square inline card below the My Gallr section tabs; no modal interruption.
- Route the CTA to the existing account surface.
- Inject one shared repository through Android and iOS roots.

## Gates

- Shared-first persistence: PASS.
- Test-first eligibility and persistence: REQUIRED.
- Truthful claims: REQUIRED; local archive/follows are explicitly device-only.
- Permission minimization: PASS.
