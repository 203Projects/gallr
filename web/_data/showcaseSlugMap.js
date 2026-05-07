// Computed at build time — used by web/_includes/hero.html.
// Maps each exhibition id to its slug so the hero marquee can link tiles
// to their detail pages without doing a per-tile selectattr filter inside
// a Nunjucks loop.
const exhibitions = require("./exhibitions.json");

module.exports = function () {
  const out = {};
  for (const e of exhibitions.exhibitions || []) {
    if (e.id && e.slug) out[e.id] = e.slug;
  }
  return out;
};
