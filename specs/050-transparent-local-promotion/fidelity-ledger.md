# Fidelity ledger: Transparent Local Promotion

Compared on 2026-07-31 against the approved desktop and mobile concepts at 1440 px and 390 px.

1. **Separation:** The placement remains a bordered region above, and outside, the organic catalogue grid. The organic card DOM order is unchanged.
2. **Disclosure:** `Promoted near you`, `Paid placement`, the once-per-day limit, and the expandable `Why am I seeing this?` explanation remain visible at both sizes.
3. **Canonical content:** The promotion CTA resolves its exhibition ID to the existing public detail URL; it does not create a paid detail route or duplicate catalogue record.
4. **Visual system:** The implementation keeps square corners, monochrome typography and surfaces, 1 px rules, the existing Inter/Gothic A1 stack, and reserves `#FF5400` for the primary CTA/active controls.
5. **Responsive behavior:** Desktop uses a horizontal media/content/action composition. At 390 px it becomes a single bordered band with a full-width CTA and no horizontal overflow.

No fidelity correction remained after the final browser pass. The test mock intentionally omitted cover media, exercising the neutral square fallback; production delivery uses the canonical published cover when present.
