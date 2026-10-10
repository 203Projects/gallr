"use strict";

// Reads for the shared route page, all with the publishable key (spec 089, E-D19). A route is read by its id through
// get_published_route, which returns null for a route that is missing, unpublished or revoked, so the page cannot
// tell them apart; the route tables themselves are not readable without an account (090 eng D4).

const { supabaseApiHeaders } = require("../../scripts/supabase-api-headers.js");

const CATALOGUE_COLUMNS = [
  "id",
  "name_ko",
  "name_en",
  "venue_name_ko",
  "venue_name_en",
  "city_en",
  "opening_date",
  "closing_date",
  "hours",
  "address_ko",
  "cover_image_url",
].join(",");

function createRouteData({ supabaseUrl, apiKey, fetch: fetchImpl = globalThis.fetch }) {
  const base = String(supabaseUrl || "").replace(/\/+$/, "");
  if (!base) throw new Error("SUPABASE_URL is required");
  const headers = supabaseApiHeaders(apiKey);

  async function getJson(path) {
    const response = await fetchImpl(`${base}${path}`, { headers: { ...headers, accept: "application/json" } });
    if (!response.ok) throw new Error(`read failed with ${response.status}`);
    return response.json();
  }

  return {
    /** The published route with its stops in order, in one request; null when it cannot be shown. */
    async loadRoute(id) {
      const response = await fetchImpl(`${base}/rest/v1/rpc/get_published_route`, {
        method: "POST",
        headers: { ...headers, accept: "application/json", "content-type": "application/json" },
        body: JSON.stringify({ p_id: id }),
      });
      if (!response.ok) throw new Error(`read failed with ${response.status}`);
      const route = await response.json();
      return route && typeof route === "object" && !Array.isArray(route) ? route : null;
    },

    /** Current catalogue rows for these exhibition ids; ids no longer listed are simply absent. */
    async loadCatalogue(ids) {
      if (ids.length === 0) return [];
      const list = ids.map((id) => `"${String(id).replace(/"/g, "")}"`).join(",");
      return getJson(
        `/rest/v1/exhibition_catalog_v2?id=in.(${encodeURIComponent(list)})&select=${encodeURIComponent(CATALOGUE_COLUMNS)}`,
      );
    },

    async loadAuthorName(ownerId) {
      const rows = await getJson(`/rest/v1/profiles?id=eq.${encodeURIComponent(ownerId)}&select=display_name`);
      const name = Array.isArray(rows) && rows[0] ? rows[0].display_name : null;
      return typeof name === "string" && name.trim() ? name.trim() : null;
    },

    async recordEvent(routeId, event, shared) {
      const response = await fetchImpl(`${base}/rest/v1/rpc/record_route_page_event`, {
        method: "POST",
        headers: { ...headers, "content-type": "application/json" },
        body: JSON.stringify({ p_route_id: routeId, p_event: event, p_shared: shared }),
      });
      if (!response.ok) throw new Error(`event failed with ${response.status}`);
    },
  };
}

module.exports = { createRouteData };
