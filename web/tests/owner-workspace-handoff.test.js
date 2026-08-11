const assert = require("assert").strict;
const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const submitPage = fs.readFileSync(path.join(root, "submit/index.html"), "utf8");
const eleventyConfig = fs.readFileSync(path.join(root, ".eleventy.js"), "utf8");
const environmentExample = fs.readFileSync(
  path.join(root, ".env.local.example"),
  "utf8"
);

assert.equal(
  submitPage.includes('href="{{ site.galleryWorkspaceUrl }}"'),
  true,
  "the public submission route must hand off to the owner workspace"
);
assert.equal(submitPage.includes("data-submit-form"), false);
assert.equal(submitPage.includes("/submit/submit.js"), false);
assert.equal(fs.existsSync(path.join(root, "submit/submit.js")), false);

for (const retiredName of [
  "GALLR_SUBMISSION_ENDPOINT",
  "GALLR_SUBMISSION_TOKEN_SECRET",
]) {
  assert.equal(eleventyConfig.includes(retiredName), false);
  assert.equal(environmentExample.includes(retiredName), false);
}
assert.equal(eleventyConfig.includes('addPassthroughCopy("submit/submit.js")'), false);
assert.equal(eleventyConfig.includes('addGlobalData("submissionEndpoint"'), false);

console.log("[owner-workspace-handoff.test] all tests passed");
