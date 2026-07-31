function impactEndpoint() {
  const configured = process.env.GALLR_IMPACT_ENDPOINT?.trim();
  if (configured) return configured;
  const supabaseUrl = process.env.SUPABASE_URL?.trim().replace(/\/+$/, "");
  return supabaseUrl ? `${supabaseUrl}/functions/v1/record-exhibition-view` : "";
}

function rsvpEndpoint() {
  const configured = process.env.GALLR_RSVP_ENDPOINT?.trim();
  if (configured) return configured;
  const supabaseUrl = process.env.SUPABASE_URL?.trim().replace(/\/+$/, "");
  return supabaseUrl ? `${supabaseUrl}/functions/v1/launch-rsvp` : "";
}

function promotionEndpoint() {
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
    const d = new Date();
    return `${d.getUTCFullYear()} / ${String(d.getUTCMonth() + 1).padStart(2, "0")}`;
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
