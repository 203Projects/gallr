import { editorInvitationRedirect } from "./backend.ts";

function assertEquals(actual: unknown, expected: unknown): void {
  if (actual !== expected) {
    throw new Error(`Expected ${String(expected)}, received ${String(actual)}`);
  }
}

Deno.test("editor invitations return to the dedicated editor portal", () => {
  const redirect = editorInvitationRedirect({
    EDITOR_PORTAL_URL: "https://editor.gallrmap.com",
  });

  assertEquals(
    redirect,
    "https://editor.gallrmap.com/?onboarding=editor",
  );
});

Deno.test("editor invitation redirect discards configured paths and fragments", () => {
  const redirect = editorInvitationRedirect({
    EDITOR_PORTAL_URL: "https://editor.gallrmap.com/old?source=admin#invite",
  });

  assertEquals(
    redirect,
    "https://editor.gallrmap.com/?onboarding=editor",
  );
});
