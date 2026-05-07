// Computed at build time — used by /exhibitions/index.html filter sidebar.
// Reads the post-fetch _data/exhibitions.json and emits a sorted list of
// city filter items (with an "All cities" leader).
const exhibitions = require("./exhibitions.json");

module.exports = function () {
  const set = new Set(
    (exhibitions.exhibitions || []).map((e) => e.city).filter(Boolean)
  );
  const items = [{ value: "all", labelKo: "전체 도시", labelEn: "ALL CITIES" }];
  for (const c of [...set].sort()) {
    items.push({ value: c.toLowerCase(), labelKo: c, labelEn: c.toUpperCase() });
  }
  return items;
};
