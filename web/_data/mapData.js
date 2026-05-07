// Computed at build time — used by /map/index.html.
// Filters out closed rows and rows missing lat/lng (the Naver SDK can't
// place a pin without coordinates). Exposes:
//   mapData.list      — array consumed by the sidebar template (full row)
//   mapData.island    — array serialized into the JSON <script> island
//                        consumed by client/map.js
const exhibitions = require("./exhibitions.json");

module.exports = function () {
  const rows = (exhibitions.exhibitions || [])
    .filter((e) => e.status !== "closed")
    .filter((e) => Number.isFinite(e.latitude) && Number.isFinite(e.longitude));
  const island = rows.map((e) => ({
    id: e.id,
    slug: e.slug,
    nameKo: e.name_ko,
    venueKo: e.venue_name_ko,
    addressKo: e.address_ko,
    status: e.status,
    lat: e.latitude,
    lng: e.longitude,
  }));
  return { list: rows, island };
};
