import {
  EditorInviteAuthorizationError,
  type EditorInviteBackend,
} from "./backend.ts";
import { createInviteEditorHandler } from "./handler.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

const environment: Record<string, string> = {
  SUPABASE_URL: "https://project.supabase.co",
  SUPABASE_PUBLISHABLE_KEY: "sb_publishable_test",
  SUPABASE_SECRET_KEY: "sb_secret_test",
  ADMIN_PORTAL_URL: "https://admin.gallrmap.com",
};

const validPayload = {
  email: "mina@example.com",
  editor_id: "mina-kim",
  name_ko: "김미나",
  name_en: "Mina Kim",
  title_ko: "객원 에디터",
  title_en: "Guest Editor",
  bio_ko: "서울의 동시대 미술을 씁니다.",
  bio_en: "Writes about contemporary art in Seoul.",
  curation_description_ko: "서울의 전시를 새롭게 연결합니다.",
  curation_description_en: "Connecting Seoul exhibitions anew.",
  is_active: false,
  active_from: "2026-08-10",
  active_to: null,
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
    request({ ...validPayload, editor_id: "Mina Kim" }),
  );

  assert(invalid.status === 400, "expected validation error");
  assert(invitations === 0, "invitation ran with invalid input");
});

Deno.test("invite editor sends and links a valid admin request", async () => {
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
        editor_id: payload.editor_id,
        email: payload.email,
        name_ko: payload.name_ko,
        name_en: payload.name_en,
        is_active: payload.is_active,
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
  assert(body.editor_id === "mina-kim", "created editor was not returned");
  assert(!JSON.stringify(body).includes("signature"), "authorization leaked");
});
