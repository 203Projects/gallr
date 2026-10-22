const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const nunjucks = require("nunjucks");

const env = new nunjucks.Environment(null, { autoescape: true });
require("../.eleventy.js")({
  addFilter: (name, filter) => env.addFilter(name, filter),
  addPassthroughCopy() {},
  addGlobalData() {},
  addShortcode() {},
  setTemplateFormats() {},
});
const template = fs.readFileSync(path.join(__dirname, "../map/index.html"), "utf8");
for (const value of [
  '</script><script>globalThis.mapInjected = true</script>',
  '</ScRiPt ><img src=x onerror="globalThis.mapInjected = true">',
  '<!--<script> & " 한글 \u2028\u2029',
  'Ordinary title / 전시 <2026> & friends',
]) {
  const island = [{ nameKo: value, venueEn: value, lat: 37.5, lng: 127 }];
  const html = env.renderString(template, { mapData: { list: [], island }, site: {} });
  const json = html.match(/<script type="application\/json" id="exhibitions-data">([\s\S]*?)<\/script>/)[1];
  assert.ok(!json.includes("<"), "catalogue data must not introduce HTML raw-text delimiters");
  assert.deepEqual(JSON.parse(json), island, "the client receives the original catalogue values");
  assert.ok(!html.includes('<img src=x'), "data must not become an HTML element");
}
console.log("map-json-island: safe raw-text serialization and value round-trip passed");
