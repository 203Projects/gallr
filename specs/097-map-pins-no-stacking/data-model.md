# Data model: Map pins without stacking

No stored data changes. These are the in-memory values the Map tab computes for one view (research R2, R4).

## PinVisualCandidate (exists)

One exhibition's place projected to the screen: `id` (exhibition id), `xPx`, `yPx` (the tip's real position).

## ArrangedMapPin (new)

What the map draws for one view.

| Field | Meaning |
|-------|---------|
| `ids` | One exhibition id for a single pin; two or more for a count pin |
| `xPx`, `yPx` | Where the tip is drawn: the real place for a pin that overlaps nothing, a lattice slot for a moved pin, the members' centroid for a count pin |
| `isMoved` | True when a single pin was placed on a lattice slot (used to narrow its accessible target, R5) |

Rules:
- At zoom ≥ `PIN_SPREAD_FROM_ZOOM` every `ArrangedMapPin` has exactly one id, and no two overlap
  (`(dx / 52dp)² + (dy / 56dp)² ≥ 1`).
- Below it, ids within 28dp (transitively) share one `ArrangedMapPin` at their centroid, as today.
- Same input positions, ids, zoom side of the threshold and density always give the same arrangement.

## CountPinCameraTarget (new)

`centerXPx`, `centerYPx` (the members' centroid on screen, converted to a map position by the caller) and `zoom`
(13.0–14.0, research R4).

## Removed

`OverlapExhibitionPresentation` and the overlap sheet's title and metadata (research R6).
