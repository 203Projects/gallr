import { validateWorkerToken } from "./auth.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

Deno.test("worker token accepts a long diverse opaque secret", () => {
  const result = validateWorkerToken("v7B!k2Z@p9Q#m4T$x8N%r5W&c3Y*e6L+");
  assert(result.valid, result.reason ?? "expected token to be valid");
});

Deno.test("worker token rejects short or low-diversity values", () => {
  assert(
    !validateWorkerToken("Short1!").valid,
    "expected short token rejection",
  );
  assert(
    !validateWorkerToken("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa11").valid,
    "expected low-diversity token rejection",
  );
});
