import { assertEquals, assertThrows } from "@std/assert";

import {
  AccountDeletionBackendError,
  parsePreparationDecision,
  validatedSupabaseUrl,
} from "./backend.ts";

Deno.test("validatedSupabaseUrl accepts hosted HTTPS and loopback HTTP", () => {
  assertEquals(
    validatedSupabaseUrl({ SUPABASE_URL: "https://example.supabase.co/" }),
    "https://example.supabase.co",
  );
  assertEquals(
    validatedSupabaseUrl({ SUPABASE_URL: "http://127.0.0.1:54321" }),
    "http://127.0.0.1:54321",
  );
});

Deno.test("validatedSupabaseUrl rejects missing and insecure hosted URLs", () => {
  for (const value of [undefined, "http://example.supabase.co", "not a url"]) {
    assertThrows(
      () => validatedSupabaseUrl({ SUPABASE_URL: value }),
      AccountDeletionBackendError,
    );
  }
});

Deno.test("parsePreparationDecision accepts the stable allowed contract", () => {
  assertEquals(
    parsePreparationDecision({
      allowed: true,
      request_id: "00000000-0000-0000-0000-000000000001",
    }),
    {
      allowed: true,
      requestId: "00000000-0000-0000-0000-000000000001",
    },
  );
});

Deno.test("parsePreparationDecision accepts bounded expected denials", () => {
  assertEquals(
    parsePreparationDecision({
      allowed: false,
      code: "account_deletion_rate_limited",
      retry_after_seconds: 300,
    }),
    {
      allowed: false,
      code: "account_deletion_rate_limited",
      retryAfterSeconds: 300,
    },
  );
  assertEquals(
    parsePreparationDecision({
      allowed: false,
      code: "account_deletion_requires_support",
    }),
    {
      allowed: false,
      code: "account_deletion_requires_support",
      retryAfterSeconds: undefined,
    },
  );
});

Deno.test("parsePreparationDecision rejects malformed or invented states", () => {
  for (
    const value of [
      null,
      { allowed: true, request_id: "not-a-uuid" },
      { allowed: false, code: "invented" },
      {
        allowed: false,
        code: "account_deletion_rate_limited",
        retry_after_seconds: 901,
      },
    ]
  ) {
    assertThrows(
      () => parsePreparationDecision(value),
      AccountDeletionBackendError,
    );
  }
});
