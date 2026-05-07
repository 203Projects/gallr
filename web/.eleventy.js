module.exports = function (eleventyConfig) {
  // Pass static assets through to dist/ root unchanged
  // {"public": "."} maps public/* → dist/*  (fonts at /fonts/, favicon at /favicon.svg)
  eleventyConfig.addPassthroughCopy({ public: "." });
  eleventyConfig.addPassthroughCopy("styles");
  eleventyConfig.addPassthroughCopy("scripts/main.js");

  // Renders today's date as "YYYY / MM" — used in the hero eyebrow row.
  eleventyConfig.addShortcode("currentYearMonth", () => {
    const d = new Date();
    return `${d.getUTCFullYear()} / ${String(d.getUTCMonth() + 1).padStart(2, "0")}`;
  });

  // Enable Nunjucks for templates; 11ty.js for JS-rendered pagination (detail pages).
  eleventyConfig.setTemplateFormats(["html", "njk", "11ty.js"]);

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
