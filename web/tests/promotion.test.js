const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const root = path.join(__dirname, "..");
const template = fs.readFileSync(path.join(root, "exhibitions", "index.html"), "utf8");
const source = fs.readFileSync(path.join(root, "client", "promotion.js"), "utf8");

function element() {
  return {
    hidden: true,
    textContent: "",
    attributes: {},
    setAttribute(name, value) { this.attributes[name] = value; },
    removeAttribute(name) { delete this.attributes[name]; },
  };
}

async function execute(city = "서울", responseStatus = 200) {
  const calls = [];
  const pending = [];
  const parts = Object.fromEntries([
    "[data-promotion]", "[data-promotion-title]", "[data-promotion-venue]",
    "[data-promotion-locality]", "[data-promotion-dates]", "[data-promotion-link]",
    "[data-promotion-image]",
  ].map((key) => [key, element()]));
  parts["[data-promotion]"].dataset = { endpoint: "https://project.test/promoted-nearby" };
  const cardLink = { getAttribute: () => "/exhibitions/between-seasons/" };
  const storage = new Map();
  const context = {
    window: { location: { search: city ? `?city=${encodeURIComponent(city)}` : "" }, addEventListener() {} },
    navigator: { doNotTrack: "0" },
    localStorage: {
      getItem: (key) => storage.get(key) ?? null,
      setItem: (key, value) => storage.set(key, value),
    },
    crypto: { randomUUID: () => "00000000-0000-4000-8000-000000000001" },
    document: {
      querySelector(selector) {
        if (selector.startsWith(".exhibition-card[data-exhibition-id")) return cardLink;
        return parts[selector] ?? null;
      },
      addEventListener() {},
    },
    fetch: (...args) => {
      calls.push(args);
      const result = Promise.resolve(responseStatus === 200 ? {
        status: 200,
        ok: true,
        json: async () => ({ placement: {
          promotion_id: "promotion-one", exhibition_id: "between-seasons",
          name_ko: "계절 사이", name_en: "Between Seasons",
          venue_name_ko: "아틀리에 한남", venue_name_en: "Atelier Hannam",
          city_ko: "서울", city_en: "Seoul", region_ko: "용산구", region_en: "Yongsan-gu",
          opening_date: "2026-08-08", closing_date: "2026-09-14",
          cover_image_url: null, disclosure: "promoted_placement",
        } }),
      } : { status: 204, ok: true });
      pending.push(result);
      return result;
    },
    URLSearchParams,
    JSON,
    encodeURIComponent,
  };
  vm.runInNewContext(source, context);
  await Promise.all(pending);
  await new Promise((resolve) => setImmediate(resolve));
  return { calls, parts, storage };
}

(async () => {
  assert.match(template, /data-promotion-endpoint="\{\{ promotionEndpoint \}\}"/);
  assert.match(template, /Promoted near you/);
  assert.match(template, /Promoted placement.*once per day/s);
  assert.ok(template.indexOf("data-promotion") < template.indexOf("discover-page__grid"),
    "promotion must precede and remain outside organic results");

  const shown = await execute();
  assert.equal(shown.calls.length, 1);
  assert.deepEqual(JSON.parse(shown.calls[0][1].body), {
    installation_key: "00000000-0000-4000-8000-000000000001",
    city_ko: "서울",
    region_ko: "",
  });
  assert.equal(shown.parts["[data-promotion]"].hidden, false);
  assert.equal(shown.parts["[data-promotion-title]"].textContent, "계절 사이");
  assert.equal(shown.parts["[data-promotion-link]"].attributes.href, "/exhibitions/between-seasons/");
  assert.equal((await execute("", 200)).calls.length, 0, "all-cities view requested promotion");
  assert.equal((await execute("서울", 204)).parts["[data-promotion]"].hidden, true);
  console.log("promotion tests passed");
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
