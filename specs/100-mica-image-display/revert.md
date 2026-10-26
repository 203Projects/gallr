# Owner decision: restore shared web presentation

The owner withdrew the MICA-specific web treatment on 25 October 2026 Pacific
to retain uniform exhibition cards and status-tag placement. The original spec
and implementation notes remain as history.

Restore the common 4:3 cover rendering, normal hover behavior and status tag
over the image on the listing; restore common cover rendering on detail. Remove
the asset-specific yellow mat and display policy. The original uploaded image
and all exhibition facts remain intact. The shared treatment can crop images.

Constitution check: this is a read-only web presentation rollback, with no new
consumer flow or shared/native business rule. No database, native app or store
release is involved. Update the existing desktop/mobile regression before the
rollback, run the full web suite and verify the deployed card and detail page.
Integrate through develop and a dedicated main promotion PR. Revise the unsent
organizer reply after production verification so it does not claim an active
web full-image fix or full-image viewing by tapping a cropped detail card.

Local validation: both updated MICA regressions failed against the exception
(`cover` expected, `contain` received) before the rollback. The complete web
suite then passed, including 130 browser tests, build, contracts and WCAG AA
checks. Web files now match the pre-exception runtime at 98594846; only the
retained MICA regression differs to guard shared crop and tag placement.
