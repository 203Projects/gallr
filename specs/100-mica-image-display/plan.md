# Implementation plan

## Constitution check, before implementation

- I/III: smallest presentation correction; add a failing regression first.
- VI: web-only presentation belongs in Eleventy templates and styles; no KMP
  business rules, models, networking or native adapters change.
- VII: existing read-only web listing/detail presentation; no consumer capability
  or new write flow. Mobile binaries are outside this requested website fix.
- Design: preserve gallr's layout, typography and monochrome chrome. The owner
  explicitly approved image-derived yellow inside this exhibition's image frame.

## Design and second constitution check

Use one build-time presentation policy, keyed to the MICA exhibition and exact
approved cover. Both templates opt into contain with a sampled sRGB background;
card hover stays unscaled and its badge moves into the caption. Unknown/new
covers retain the normal treatment. This is display metadata, not catalogue
data, so no database migration or alternate writer is needed. Principles VI
and VII remain satisfied.

Run the full web suite, inspect desktop/mobile rendering, integrate through a
develop PR and a dedicated main promotion PR, then verify both live views and
save the reply in the original Gmail thread. No paid AI service is introduced.
