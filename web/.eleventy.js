const crypto = require("crypto");

// Daily-rotating submission token. Must match isValidSubmissionToken() in
// gas/FormEndpoint.gs: base64( HMAC_SHA256(secret, "gallr-submit:" + UTC-date) ).
// The GAS endpoint accepts the current and previous UTC day, so a deploy that
// is up to ~24h stale still validates.
function submissionToken(secret) {
  if (!secret) return "";
  const dayKey = new Date().toISOString().slice(0, 10);
  return crypto
    .createHmac("sha256", secret)
    .update("gallr-submit:" + dayKey)
    .digest("base64");
}

module.exports = function (eleventyConfig) {
  // Pass static assets through to dist/ root unchanged
  // {"public": "."} maps public/* → dist/*  (fonts at /fonts/, favicon at /favicon.svg)
  eleventyConfig.addPassthroughCopy({ public: "." });
  eleventyConfig.addPassthroughCopy("styles");
  eleventyConfig.addPassthroughCopy("scripts/main.js");
  eleventyConfig.addPassthroughCopy({ "client": "scripts" });
  eleventyConfig.addPassthroughCopy("submit/submit.js");
  eleventyConfig.addGlobalData("submissionEndpoint", process.env.GALLR_SUBMISSION_ENDPOINT || "");
  eleventyConfig.addGlobalData(
    "submissionToken",
    submissionToken(process.env.GALLR_SUBMISSION_TOKEN_SECRET || "")
  );

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
