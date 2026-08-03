const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const root = path.join(__dirname, "..");
const source = fs.readFileSync(
  path.join(root, "client", "exhibition-impact.js"),
  "utf8",
);
const template = fs.readFileSync(
  path.join(root, "_includes", "detail-page.njk"),
  "utf8",
);

function configuredGlobalData(environment) {
  const previous = {
    GALLR_ENABLE_IMPACT: process.env.GALLR_ENABLE_IMPACT,
    GALLR_ENABLE_RSVP: process.env.GALLR_ENABLE_RSVP,
    GALLR_ENABLE_PROMOTION: process.env.GALLR_ENABLE_PROMOTION,
    GALLR_IMPACT_ENDPOINT: process.env.GALLR_IMPACT_ENDPOINT,
    GALLR_RSVP_ENDPOINT: process.env.GALLR_RSVP_ENDPOINT,
    GALLR_PROMOTION_ENDPOINT: process.env.GALLR_PROMOTION_ENDPOINT,
    SUPABASE_URL: process.env.SUPABASE_URL,
  };
  Object.assign(process.env, environment);
  const values = {};
  const config = {
    addPassthroughCopy() {},
    addGlobalData(name, value) { values[name] = value; },
    addShortcode() {},
    setTemplateFormats() {},
  };
  require(path.join(root, ".eleventy.js"))(config);
  for (const [name, value] of Object.entries(previous)) {
    if (value === undefined) delete process.env[name];
    else process.env[name] = value;
  }
  return values;
}

async function execute({ doNotTrack = "0", target = true } = {}) {
  const calls = [];
  const element = {
    getAttribute(name) {
      return name === "data-exhibition-impact"
        ? "exhibition-one"
        : "https://project.supabase.co/functions/v1/record-exhibition-view";
    },
  };
  const pending = [];
  const context = {
    navigator: { doNotTrack },
    window: { doNotTrack },
    document: { querySelector: () => target ? element : null },
    fetch: (...args) => {
      calls.push(args);
      const result = Promise.resolve({ ok: true });
      pending.push(result);
      return result;
    },
    JSON,
  };
  vm.runInNewContext(source, context);
  await Promise.all(pending);
  return calls;
}

(async () => {
  assert.match(template, /data-exhibition-impact="\{\{ ex\.id \}\}"/);
  assert.match(template, /\{% if impactEndpoint %\}.*exhibition-impact\.js/);
  assert.equal(
    configuredGlobalData({
      GALLR_ENABLE_IMPACT: "true",
      GALLR_IMPACT_ENDPOINT: "https://impact.example.test/record",
      SUPABASE_URL: "",
    }).impactEndpoint,
    "https://impact.example.test/record",
  );
  assert.equal(
    configuredGlobalData({
      GALLR_ENABLE_IMPACT: "1",
      GALLR_IMPACT_ENDPOINT: "",
      SUPABASE_URL: "https://project.supabase.co/",
    }).impactEndpoint,
    "https://project.supabase.co/functions/v1/record-exhibition-view",
  );
  assert.equal(
    configuredGlobalData({
      GALLR_ENABLE_IMPACT: "",
      GALLR_IMPACT_ENDPOINT: "https://impact.example.test/record",
      SUPABASE_URL: "https://project.supabase.co/",
    }).impactEndpoint,
    "",
    "R2 impact must remain dark until explicitly enabled",
  );
  const deferred = configuredGlobalData({
    GALLR_ENABLE_RSVP: "",
    GALLR_ENABLE_PROMOTION: "",
    GALLR_RSVP_ENDPOINT: "https://rsvp.example.test/record",
    GALLR_PROMOTION_ENDPOINT: "https://promotion.example.test/record",
    SUPABASE_URL: "https://project.supabase.co/",
  });
  assert.equal(deferred.rsvpEndpoint, "", "R3 RSVP must remain dark until explicitly enabled");
  assert.equal(deferred.promotionEndpoint, "", "R4 promotion must remain dark until explicitly enabled");
  const activated = configuredGlobalData({
    GALLR_ENABLE_RSVP: "true",
    GALLR_ENABLE_PROMOTION: "1",
    GALLR_RSVP_ENDPOINT: "",
    GALLR_PROMOTION_ENDPOINT: "",
    SUPABASE_URL: "https://project.supabase.co/",
  });
  assert.equal(activated.rsvpEndpoint, "https://project.supabase.co/functions/v1/launch-rsvp");
  assert.equal(activated.promotionEndpoint, "https://project.supabase.co/functions/v1/promoted-nearby");

  const calls = await execute();
  assert.equal(calls.length, 1);
  assert.equal(calls[0][0], "https://project.supabase.co/functions/v1/record-exhibition-view");
  assert.equal(calls[0][1].credentials, "omit");
  assert.equal(calls[0][1].referrerPolicy, "no-referrer");
  assert.deepEqual(
    JSON.parse(calls[0][1].body),
    { exhibition_id: "exhibition-one" },
  );

  assert.equal((await execute({ doNotTrack: "1" })).length, 0);
  assert.equal((await execute({ target: false })).length, 0);
  console.log("impact tests passed");
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
