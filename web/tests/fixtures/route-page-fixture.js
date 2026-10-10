// Spec 089 DR-D27: a rendered shared route page with fixed data, for the accessibility audit and the
// Playwright layout checks. Thursday 2026-10-08 in Seoul; one stop closed that day, one no longer listed.

const { buildRouteModel, renderRoutePage } = require("../../api/_lib/route-page.js");

const ROUTE_ID = "6f1c2a7e-8d34-4b8e-9a51-2f0c7d1e9b10";

function stop(position, id, region, city, latitude, longitude) {
  return {
    position,
    exhibition_id: id,
    name_ko: `스냅샷 전시 ${position + 1}`,
    name_en: `Snapshot show ${position + 1}`,
    venue_name_ko: `공간 ${position + 1}`,
    venue_name_en: `Venue ${position + 1}`,
    latitude,
    longitude,
    region_ko: region,
    region_en: region === "용산구" ? "Yongsan-gu" : region === "종로구" ? "Jongno-gu" : "Danwon-gu",
    city_ko: city,
  };
}

function listed(id, name, hours, address) {
  return {
    id,
    name_ko: name,
    name_en: `${name} (EN)`,
    venue_name_ko: `${name} 공간`,
    venue_name_en: `${name} Venue`,
    city_en: id === "e-ansan" ? "Ansan" : "Seoul",
    opening_date: "2026-09-01",
    closing_date: "2026-12-31",
    hours,
    address_ko: address,
    cover_image_url: null,
  };
}

const route = {
  id: ROUTE_ID,
  name: "토요일 한남에서 삼청까지 걷는 전시 동선",
  owner: "owner-1",
  stops: [
    stop(0, "e-hannam", "용산구", "서울", 37.5345, 127.0024),
    stop(1, "e-samcheong", "종로구", "서울", 37.5826, 126.9837),
    stop(2, "e-gone", "종로구", "서울", 37.5791, 126.9770),
    stop(3, "e-ansan", "단원구", "안산시", 37.3219, 126.8309),
  ],
};

const catalogue = [
  listed("e-hannam", "빛의 정원", "10am - 6pm\nTuesday - Saturday", "서울 용산구 한남대로 1"),
  listed("e-samcheong", "느린 풍경", "11:00-19:00 Friday-Sunday", "서울 종로구 삼청로 2"),
  listed("e-ansan", "바다의 기록", null, "경기 안산시 단원구 3"),
];

/** The page HTML for `lang` ("ko" or "en") and `theme` ("light" or "dark"). */
function renderRouteFixture(lang, theme) {
  const model = buildRouteModel({ route, catalogue, authorName: "hanshin", today: "2026-10-08", lang });
  const html = renderRoutePage(model, { routeId: ROUTE_ID, shared: true });
  return theme === "dark" ? html.replace('<body class="route-body">', '<body class="route-body" data-theme="dark">') : html;
}

module.exports = { renderRouteFixture, ROUTE_ID };
