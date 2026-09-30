const { supabaseApiHeaders, legacyJwtRole } = require("./supabase-api-headers.js");

function individualSubmissionConfig(env = process.env) {
  if (!["1", "true"].includes(env.GALLR_ENABLE_INDIVIDUAL_SUBMISSION?.trim().toLowerCase())) return { url: "", key: "" };
  const url = new URL(env.SUPABASE_URL || "");
  const loopback = ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
  if ((url.protocol !== "https:" && !(loopback && url.protocol === "http:")) || url.username || url.password || url.search || url.hash || !["", "/"].includes(url.pathname)) throw new Error("Invalid public submission Supabase URL");
  const key = env.SUPABASE_PUBLISHABLE_KEY?.trim() || "";
  supabaseApiHeaders(key);
  if (!key.startsWith("sb_publishable_") && legacyJwtRole(key) !== "anon") throw new Error("A publishable Supabase key is required");
  return { url: url.origin, key };
}
module.exports = { individualSubmissionConfig };
