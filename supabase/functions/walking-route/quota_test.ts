import { assertStrictEquals } from "@std/assert";
import {
  MIN_HASH_SECRET_LENGTH,
  requestSource,
  sourceDigest,
} from "./quota.ts";

Deno.test("digests the source with HMAC-SHA-256 (RFC 4231 case 2)", async () => {
  assertStrictEquals(
    await sourceDigest("Jefe", "what do ya want for nothing?"),
    "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
  );
});

Deno.test("the source is the trusted ingress address", () => {
  const request = (headers: Record<string, string>) =>
    new Request("https://project.supabase.co/functions/v1/walking-route", {
      headers,
    });
  assertStrictEquals(
    requestSource(request({
      "cf-connecting-ip": " 203.0.113.7 ",
      "x-forwarded-for": "198.51.100.1",
    })),
    "203.0.113.7",
  );
  assertStrictEquals(
    requestSource(request({ "x-forwarded-for": "198.51.100.1, 10.0.0.1" })),
    "198.51.100.1",
  );
  assertStrictEquals(requestSource(request({})), "unknown");
});

Deno.test("hash secrets are at least 32 characters", () => {
  assertStrictEquals(MIN_HASH_SECRET_LENGTH, 32);
});
