const assert = require("assert").strict;

const ENV_NAME = "GALLR_GALLERY_WORKSPACE_URL";
const originalValue = process.env[ENV_NAME];
const siteConfigPath = require.resolve("../_data/site.js");

function loadSiteConfig(value) {
  if (value === undefined) {
    delete process.env[ENV_NAME];
  } else {
    process.env[ENV_NAME] = value;
  }

  delete require.cache[siteConfigPath];
  return require(siteConfigPath);
}

try {
  assert.equal(
    loadSiteConfig(undefined).galleryWorkspaceUrl,
    "https://gallery.gallermap.com/",
    "production workspace URL must remain the default",
  );

  assert.equal(
    loadSiteConfig("  https://gallery-preview.example/  ")
      .galleryWorkspaceUrl,
    "https://gallery-preview.example/",
    "a branch-scoped workspace URL must override and trim the default",
  );

  assert.equal(
    loadSiteConfig("   ").galleryWorkspaceUrl,
    "https://gallery.gallermap.com/",
    "blank overrides must fall back to the production workspace URL",
  );

  assert.throws(
    () => loadSiteConfig("javascript:alert(1)"),
    /must use HTTP or HTTPS/,
    "unsafe workspace URL schemes must fail the build",
  );
} finally {
  if (originalValue === undefined) {
    delete process.env[ENV_NAME];
  } else {
    process.env[ENV_NAME] = originalValue;
  }
  delete require.cache[siteConfigPath];
}

console.log("[site-config.test] all tests passed");
