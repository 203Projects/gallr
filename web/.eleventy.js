function submissionEndpoint() {
  const configured = process.env.GALLR_SUBMISSION_ENDPOINT?.trim();
  if (configured) return configured;
  const supabaseUrl = process.env.SUPABASE_URL?.trim().replace(/\/+$/, "");
  return supabaseUrl ? `${supabaseUrl}/functions/v1/submit-exhibition` : "";
}

module.exports = function (eleventyConfig) {
  // Pass static assets through to dist/ root unchanged
  // {"public": "."} maps public/* → dist/*  (fonts at /fonts/, favicon at /favicon.svg)
  eleventyConfig.addPassthroughCopy({ public: "." });
  eleventyConfig.addPassthroughCopy("styles");
  eleventyConfig.addPassthroughCopy("scripts/main.js");
  eleventyConfig.addPassthroughCopy({ "client": "scripts" });
  eleventyConfig.addPassthroughCopy("submit/submit.js");
  eleventyConfig.addGlobalData("submissionEndpoint", submissionEndpoint());

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
