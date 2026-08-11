import { assertEquals, assertMatch } from "@std/assert";

import {
  type AccountDeletionBackend,
  AccountDeletionBackendError,
  type PreparationDecision,
} from "./backend.ts";
import { createDeleteAccountHandler } from "./handler.ts";

const USER_ID = "00000000-0000-0000-0000-000000000001";
const REQUEST_ID = "10000000-0000-0000-0000-000000000001";
const AUTHORIZATION = "Bearer test-token";

class FakeBackend implements AccountDeletionBackend {
  preparation: PreparationDecision = { allowed: true, requestId: REQUEST_ID };
  authenticationError: Error | null = null;
  deletionError: Error | null = null;
  cancellationError: Error | null = null;
  deletedUserIds: string[] = [];
  cancelledRequestIds: string[] = [];

  authenticate(): Promise<string> {
    if (this.authenticationError) throw this.authenticationError;
    return Promise.resolve(USER_ID);
  }

  prepare(): Promise<PreparationDecision> {
    return Promise.resolve(this.preparation);
  }

  deleteIdentity(userId: string): Promise<void> {
    this.deletedUserIds.push(userId);
    if (this.deletionError) throw this.deletionError;
    return Promise.resolve();
  }

  cancelCleanup(requestId: string): Promise<void> {
    this.cancelledRequestIds.push(requestId);
    if (this.cancellationError) throw this.cancellationError;
    return Promise.resolve();
  }
}

function handler(backend: FakeBackend) {
  return createDeleteAccountHandler({
    backend,
    requestId: () => "edge-request-id",
    log: () => undefined,
  });
}

function request(
  method = "POST",
  authorization: string | null = AUTHORIZATION,
) {
  const headers = new Headers();
  if (authorization !== null) headers.set("Authorization", authorization);
  return new Request("https://example.test/functions/v1/delete-account", {
    method,
    headers,
  });
}

async function payload(response: Response) {
  return await response.json() as Record<string, unknown>;
}

Deno.test("handler supports CORS and rejects unsupported methods", async () => {
  const backend = new FakeBackend();
  const cors = await handler(backend)(request("OPTIONS", null));
  assertEquals(cors.status, 204);
  assertEquals(
    cors.headers.get("access-control-allow-methods"),
    "POST, OPTIONS",
  );

  const rejected = await handler(backend)(request("DELETE"));
  assertEquals(rejected.status, 405);
  assertEquals(rejected.headers.get("allow"), "POST, OPTIONS");
});

Deno.test("handler rejects missing and malformed bearer authorization", async () => {
  const backend = new FakeBackend();
  for (const authorization of [null, "Basic value", "Bearer two tokens"]) {
    const response = await handler(backend)(request("POST", authorization));
    assertEquals(response.status, 401);
    assertEquals(
      (await payload(response) as { error: { code: string } }).error.code,
      "authentication_required",
    );
  }
});

Deno.test("handler maps an invalid session without exposing backend details", async () => {
  const backend = new FakeBackend();
  backend.authenticationError = new AccountDeletionBackendError(
    "authentication_required",
    "sensitive upstream detail",
  );
  const response = await handler(backend)(request());
  assertEquals(response.status, 401);
  const body = JSON.stringify(await payload(response));
  assertMatch(body, /authentication_required/u);
  assertEquals(body.includes("sensitive"), false);
});

Deno.test("handler returns distinct safe preparation denials", async () => {
  const cases: Array<[PreparationDecision, number, string]> = [
    [
      {
        allowed: false,
        code: "account_deletion_reauthentication_required",
      },
      409,
      "reauthentication_required",
    ],
    [
      { allowed: false, code: "account_deletion_requires_support" },
      409,
      "support_required",
    ],
    [
      {
        allowed: false,
        code: "account_deletion_rate_limited",
        retryAfterSeconds: 120,
      },
      429,
      "rate_limited",
    ],
  ];
  for (const [preparation, status, code] of cases) {
    const backend = new FakeBackend();
    backend.preparation = preparation;
    const response = await handler(backend)(request());
    assertEquals(response.status, status);
    assertEquals(
      (await payload(response) as { error: { code: string } }).error.code,
      code,
    );
    if (status === 429) {
      assertEquals(response.headers.get("retry-after"), "120");
    }
    assertEquals(backend.deletedUserIds, []);
  }
});

Deno.test("handler deletes the verified identity and returns the durable request", async () => {
  const backend = new FakeBackend();
  const response = await handler(backend)(request());
  assertEquals(response.status, 200);
  assertEquals(await payload(response), {
    status: "deleted",
    request_id: REQUEST_ID,
  });
  assertEquals(backend.deletedUserIds, [USER_ID]);
  assertEquals(backend.cancelledRequestIds, []);
  assertEquals(response.headers.get("cache-control"), "no-store");
});

Deno.test("handler cancels cleanup when Auth deletion fails", async () => {
  const backend = new FakeBackend();
  backend.deletionError = new AccountDeletionBackendError(
    "identity_deletion_failed",
    "upstream detail",
  );
  const response = await handler(backend)(request());
  assertEquals(response.status, 503);
  assertEquals(
    (await payload(response) as { error: { code: string } }).error.code,
    "account_deletion_unavailable",
  );
  assertEquals(backend.cancelledRequestIds, [REQUEST_ID]);
});

Deno.test("handler maps the database race guard to assisted deletion", async () => {
  const backend = new FakeBackend();
  backend.deletionError = new AccountDeletionBackendError(
    "account_deletion_requires_support",
    "protected",
  );
  const response = await handler(backend)(request());
  assertEquals(response.status, 409);
  assertEquals(
    (await payload(response) as { error: { code: string } }).error.code,
    "support_required",
  );
  assertEquals(backend.cancelledRequestIds, [REQUEST_ID]);
});

Deno.test("handler preserves cleanup when the deletion result is ambiguous", async () => {
  const backend = new FakeBackend();
  backend.deletionError = new AccountDeletionBackendError(
    "identity_deletion_status_unknown",
    "network result unknown",
  );
  const response = await handler(backend)(request());
  assertEquals(response.status, 503);
  assertEquals(
    (await payload(response) as { error: { code: string } }).error.code,
    "deletion_status_unknown",
  );
  assertEquals(backend.cancelledRequestIds, []);
});
