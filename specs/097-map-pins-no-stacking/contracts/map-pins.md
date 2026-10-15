# Contract: Map tab pins

Visible behaviour of `SeoulExhibitionMap` (Map tab, both "전체 전시" and "내 전시").

## Arrangement

| Zoom | Pins |
|------|------|
| below 13.0 | Pins within 28dp share one count pin (first cover, count badge, orange ring when any member is saved) |
| 13.0 and closer | One pin per exhibition with its own cover; pins that would overlap move onto a 52 × 56dp brick lattice around their group's centre; pins that overlap nothing stay on their place; no count badge |

Saved pins draw above unsaved ones. Titles show only under single pins and only where they do not collide.

## Taps

| Target | Result |
|--------|--------|
| Single pin (moved or not) | Opens the exhibition |
| Count pin | Animates the camera to the members' centre at zoom 13.0–14.0 (fit 60% of the view); no sheet |

## Accessibility

| Target | Label (KO / EN) | Action | Width |
|--------|-----------------|--------|-------|
| Single pin | Exhibition name | Opens it | 104dp, or 52dp when another pin is within 104dp horizontally |
| Count pin | "전시 N개 · 확대" / "N EXHIBITIONS · ZOOM IN" | Zooms in | 52dp |

Targets sit over the drawn positions and are shown only when fully inside the view (as today).
