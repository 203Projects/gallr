import "@supabase/functions-js/edge-runtime.d.ts";
import { createLaunchRsvpBackend } from "./backend.ts";
import { createLaunchRsvpHandler } from "./handler.ts";

Deno.serve(createLaunchRsvpHandler({
  env: (name) => Deno.env.get(name),
  createBackend: createLaunchRsvpBackend,
}));
