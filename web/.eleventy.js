const { currentYearMonth } = require("./scripts/lib/site-date.js");

function releaseSliceEnabled(name) {
  const value = process.env[name]?.trim().toLowerCase();
  return value === "1" || value === "true";
}

function impactEndpoint() {
  if (!releaseSliceEnabled("GALLR_ENABLE_IMPACT")) return "";
  const configured = process.env.GALLR_IMPACT_ENDPOINT?.trim();
  if (configured) return configured;
  const supabaseUrl = process.env.SUPABASE_URL?.trim().replace(/\/+$/, "");
  return supabaseUrl ? `${supabaseUrl}/functions/v1/record-exhibition-view` : "";
}

function rsvpEndpoint() {
  if (!releaseSliceEnabled("GALLR_ENABLE_RSVP")) return "";
  const configured = process.env.GALLR_RSVP_ENDPOINT?.trim();
  if (configured) return configured;
  const supabaseUrl = process.env.SUPABASE_URL?.trim().replace(/\/+$/, "");
  return supabaseUrl ? `${supabaseUrl}/functions/v1/launch-rsvp` : "";
}

function promotionEndpoint() {
  if (!releaseSliceEnabled("GALLR_ENABLE_PROMOTION")) return "";
  const configured = process.env.GALLR_PROMOTION_ENDPOINT?.trim();
  if (configured) return configured;
  const supabaseUrl = process.env.SUPABASE_URL?.trim().replace(/\/+$/, "");
  return supabaseUrl ? `${supabaseUrl}/functions/v1/promoted-nearby` : "";
}

module.exports = function (eleventyConfig) {
  // Pass static assets through to dist/ root unchanged
  // {"public": "."} maps public/* → dist/*  (fonts at /fonts/, favicon at /favicon.svg)
  eleventyConfig.addPassthroughCopy({ public: "." });
  eleventyConfig.addPassthroughCopy("styles");
  eleventyConfig.addPassthroughCopy("scripts/main.js");
  eleventyConfig.addPassthroughCopy({ "client": "scripts" });
  eleventyConfig.addPassthroughCopy("rsvp/rsvp.js");
  eleventyConfig.addGlobalData("impactEndpoint", impactEndpoint());
  eleventyConfig.addGlobalData("rsvpEndpoint", rsvpEndpoint());
  eleventyConfig.addGlobalData("promotionEndpoint", promotionEndpoint());

  // Renders today's date as "YYYY / MM" — used in the hero eyebrow row.
  eleventyConfig.addShortcode("currentYearMonth", () => {
    return currentYearMonth(process.env.GALLR_TEST_TODAY);
  });

  // Enable Nunjucks for templates.
  eleventyConfig.setTemplateFormats(["html", "njk"]);

  return {
    dir: {
      output: "dist",
      includes: "_includes",
      data: "_data",
    },
    htmlTemplateEngine: "njk",
    markdownTemplateEngine: "njk",
  };
};
