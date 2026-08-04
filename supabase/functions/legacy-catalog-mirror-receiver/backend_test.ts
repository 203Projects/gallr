import { createLegacyCatalogReceiverBackend } from "./backend.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

Deno.test("receiver uses only its local Singapore secret for the guarded RPC", async () => {
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  const backend = createLegacyCatalogReceiverBackend({
    SUPABASE_URL: "https://yhuhjxswjbrtmbpbrciq.supabase.co",
    SUPABASE_SECRET_KEY: "local-target-secret",
  }, (input, init) => {
    calls.push({ url: String(input), init });
    return Promise.resolve(
      new Response(JSON.stringify({ status: "unchanged" }), {
        status: 200,
      }),
    );
  });
  const status = await backend.apply({
    p_snapshot: { exhibitions: [{ id: "show" }], events: [], editors: [] },
    p_source_project_ref: "oqrvbstopuppznxqoonp",
    p_reason: "test receiver",
  });
  assert(status === "unchanged", "wrong receipt returned");
  assert(calls.length === 1, "RPC not called exactly once");
  assert(
    calls[0].url ===
      "https://yhuhjxswjbrtmbpbrciq.supabase.co/rest/v1/rpc/service_replace_legacy_mobile_catalog",
    "wrong RPC target",
  );
  const headers = new Headers(calls[0].init?.headers);
  assert(headers.get("apikey") === "local-target-secret", "local key not used");
});

Deno.test("receiver refuses deployment outside Singapore", () => {
  let message = "";
  try {
    createLegacyCatalogReceiverBackend({
      SUPABASE_URL: "https://oqrvbstopuppznxqoonp.supabase.co",
      SUPABASE_SECRET_KEY: "wrong-project-secret",
    });
  } catch (error) {
    message = error instanceof Error ? error.message : String(error);
  }
  assert(
    message === "Receiver project configuration is invalid.",
    "receiver accepted wrong project",
  );
});
