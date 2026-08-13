import {
  EditorInviteAuthorizationError,
  type EditorInviteBackend,
  EditorInviteFailure,
} from "./backend.ts";
import { createInviteEditorHandler } from "./handler.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

const environment: Record<string, string> = {
  SUPABASE_URL: "https://project.supabase.co",
  SUPABASE_PUBLISHABLE_KEY: "sb_publishable_test",
  SUPABASE_SECRET_KEY: "sb_secret_test",
  EDITOR_PORTAL_URL: "https://editor.gallrmap.com",
};

const validPayload = {
  email: "mina@example.com",
};

function request(body: unknown): Request {
  return new Request("https://project.supabase.co/functions/v1/invite-editor", {
    method: "POST",
    headers: {
      Authorization: "Bearer header.payload.signature",
      "Content-Type": "application/json",
      Origin: "https://admin.gallrmap.com",
    },
    body: JSON.stringify(body),
  });
}

Deno.test("invite editor requires admin before reading the request body", async () => {
  let invitations = 0;
  const backend: EditorInviteBackend = {
    authorizeAdmin: () => {
      throw new EditorInviteAuthorizationError("admin_role_required");
    },
    invite: () => {
      invitations += 1;
      return Promise.reject(new Error("must not run"));
    },
  };
  const handler = createInviteEditorHandler({
    env: (name) => environment[name],
    createBackend: () => backend,
  });
  const denied = await handler(request(validPayload));

  assert(denied.status === 403, "expected admin denial");
  assert(invitations === 0, "invitation ran for a non-admin caller");
});

Deno.test("invite editor validates input before sending an invitation", async () => {
  let invitations = 0;
  const backend: EditorInviteBackend = {
    authorizeAdmin: () => Promise.resolve(),
    invite: () => {
      invitations += 1;
      return Promise.reject(new Error("must not run"));
    },
  };
  const handler = createInviteEditorHandler({
    env: (name) => environment[name],
    createBackend: () => backend,
  });
  const invalid = await handler(
    request({ ...validPayload, name_ko: "must not be accepted" }),
  );

  assert(invalid.status === 400, "expected validation error");
  assert(invitations === 0, "invitation ran with invalid input");
});

Deno.test("invite editor sends and records a pending invitation", async () => {
  let receivedAuthorization = "";
  let receivedPayload: unknown;
  const backend: EditorInviteBackend = {
    authorizeAdmin: (authorization) => {
      receivedAuthorization = authorization;
      return Promise.resolve();
    },
    invite: (_authorization, payload) => {
      receivedPayload = payload;
      return Promise.resolve({
        email: payload.email,
        status: "invited",
      });
    },
  };
  const handler = createInviteEditorHandler({
    env: (name) => environment[name],
    createBackend: () => backend,
  });
  const response = await handler(request(validPayload));
  const body = await response.json() as Record<string, unknown>;

  assert(response.status === 201, "expected created response");
  assert(
    receivedAuthorization === "Bearer header.payload.signature",
    "caller authorization was not preserved",
  );
  assert(receivedPayload !== undefined, "validated payload was not forwarded");
  assert(body.email === "mina@example.com", "invited email was not returned");
  assert(body.status === "invited", "invitation status was not returned");
  assert(!JSON.stringify(body).includes("signature"), "authorization leaked");
});

Deno.test("invite editor returns a precise safe conflict", async () => {
  const backend: EditorInviteBackend = {
    authorizeAdmin: () => Promise.resolve(),
    invite: () =>
      Promise.reject(
        new EditorInviteFailure("email_already_registered"),
      ),
  };
  const handler = createInviteEditorHandler({
    env: (name) => environment[name],
    createBackend: () => backend,
  });

  const response = await handler(request(validPayload));
  const body = await response.json() as Record<string, unknown>;

  assert(response.status === 409, "expected email conflict");
  assert(body.error === "email_already_registered", "expected safe error code");
});
