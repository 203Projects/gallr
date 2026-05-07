// Computed at build time — used by /exhibitions/index.html filter sidebar.
// Reads the post-fetch _data/exhibitions.json and emits a sorted list of
// city filter items (with an "All cities" leader). The Korean city name is
// the canonical value for both labels and URL params: data-city on each
// card matches city_ko exactly, and the URL ?city=서울 round-trips via
// URLSearchParams without further encoding logic.
const exhibitions = require("./exhibitions.json");

module.exports = function () {
  const set = new Set(
    (exhibitions.exhibitions || []).map((e) => e.city_ko).filter(Boolean)
  );
  const items = [{ value: "all", labelKo: "전체 도시", labelEn: "ALL CITIES" }];
  for (const c of [...set].sort()) {
    items.push({ value: c, labelKo: c, labelEn: c });
  }
  return items;
};
