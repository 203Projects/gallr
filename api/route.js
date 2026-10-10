"use strict";

// Vercel Function behind /route/{id} and /route/{id}/events (spec 089). The Vercel project builds from the
// repository root, so the function lives here and the root vercel.json rewrites both paths to it; the page
// itself is implemented in web/api/_lib. Reads use only the publishable key, resolved like the build scripts.

const { createRouteHandler } = require("../web/api/_lib/route-handler.js");
const { createRouteData } = require("../web/api/_lib/route-data.js");
const { resolveSupabasePublicApiKey } = require("../web/scripts/supabase-public-api-key.js");

let handler = null;

module.exports = async function route(req, res) {
  if (!handler) handler = createRouteHandler({ data: dataFromEnvironment() });
  return handler(req, res);
};

/** Missing or unsafe configuration answers every request with the 503 page rather than crashing. */
function dataFromEnvironment() {
  try {
    return createRouteData({
      supabaseUrl: (process.env.SUPABASE_URL || "").trim(),
      apiKey: resolveSupabasePublicApiKey(process.env),
    });
  } catch (error) {
    console.log(JSON.stringify({ component: "route_page", outcome: "misconfigured" }));
    const unavailable = async () => {
      throw error;
    };
    return { loadRoute: unavailable, loadCatalogue: unavailable, loadAuthorName: unavailable, recordEvent: unavailable };
  }
}
