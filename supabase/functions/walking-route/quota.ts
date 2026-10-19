/** Shorter secrets would make the hashed source guessable from the small IPv4 space. */
export const MIN_HASH_SECRET_LENGTH = 32;
export const MAX_HASH_SECRET_LENGTH = 256;

/** The trusted ingress address, as `mobile-analytics` reads it. */
export function requestSource(request: Request): string {
  return request.headers.get("cf-connecting-ip")?.trim() ||
    request.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "unknown";
}

/** HMAC-SHA-256 of the source under WALKING_ROUTE_HASH_SECRET, lowercase hex; the raw address goes nowhere. */
export async function sourceDigest(
  secret: string,
  source: string,
): Promise<string> {
  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "HMAC",
    key,
    encoder.encode(source),
  );
  return Array.from(new Uint8Array(signature))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}
