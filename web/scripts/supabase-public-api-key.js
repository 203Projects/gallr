"use strict";

function resolveSupabasePublicApiKey(environment = process.env) {
  const candidates = [
    environment.SUPABASE_PUBLISHABLE_KEY,
    environment.SUPABASE_ANON_KEY,
  ];

  return candidates.map((value) => value?.trim()).find(Boolean) || "";
}

module.exports = { resolveSupabasePublicApiKey };
