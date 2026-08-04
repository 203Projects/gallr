import "@supabase/functions-js/edge-runtime.d.ts";
import { createLaunchWebhookBackend } from "./backend.ts";
import { createLaunchWebhookHandler } from "./handler.ts";

Deno.serve(createLaunchWebhookHandler({
  env: (name) => Deno.env.get(name),
  createBackend: createLaunchWebhookBackend,
}));
