# Data Model: Mobile Map UI Regressions

## ExhibitionCoordinateGroup

Presentation-domain value derived from valid catalogue pins before projection.

| Field | Type | Rule |
|---|---|---|
| `position` | `Position` | Exact source latitude/longitude; immutable group key |
| `exhibitions` | `List<Exhibition>` | Non-empty; preserves catalogue order |

Derived values:

- `id`: stable first exhibition ID for one-item groups; deterministic joined/sorted identity for
  multi-item groups.
- `count`: `exhibitions.size`.
- `allSaved`: true only when every exhibition ID exists in the saved set.
- `localizedLabel`: exhibition title for a single item; localized group description for multiple.

## Map Pin GeoJSON Feature

One point feature per `ExhibitionCoordinateGroup`.

| Property | Purpose |
|---|---|
| feature `id` | Resolve a MapLibre click to the coordinate group |
| `title` | Localized one-line label for single exhibitions |
| `count` | Full group size; blank/omitted for single exhibitions |
| `saved` | Select black or orange visual layer |
| geometry | Exact group position |

The GeoJSON is ephemeral presentation state. It is not persisted and does not replace catalogue
coordinates.
