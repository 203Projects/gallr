# Dot-map geometry generation

The mobile abstract map uses derived presentation geometry, not map tiles. Its country and city
silhouettes are generated from Natural Earth vector data, which Natural Earth publishes in the public
domain for modification and commercial use.

- Source: <https://www.naturalearthdata.com/downloads/10m-cultural-vectors/>
- License: <https://www.naturalearthdata.com/about/terms-of-use/>
- Input layers: 1:10m admin-0 countries for South Korea and 1:10m admin-1 states/provinces for Seoul.
- Output: normalized, presentation-only dot cells checked into
  `composeApp/src/commonMain/composeResources/files/map_geometry/`.

The generator pins Natural Earth revision `ca96624a56bd078437bca8184e78163e5039ad19`, retains only the
two selected public-domain features, records input checksums, and writes cells in deterministic row
order.

```bash
# Refresh the two minimal source features from the pinned revision, then generate output.
node scripts/map-geometry/generate-dot-map.mjs --refresh-source

# Regenerate from checked-in source and prove stable output.
node --test scripts/map-geometry/generate-dot-map.test.mjs
```

Do not copy outlines from a map screenshot or provider tile. Real exhibition coordinates remain
separate from these derived display cells.
