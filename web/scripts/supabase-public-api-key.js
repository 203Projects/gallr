"use strict";

function resolveSupabasePublicApiKey(environment = process.env) {
  return environment.SUPABASE_PUBLISHABLE_KEY?.trim() || "";
}

module.exports = { resolveSupabasePublicApiKey };
