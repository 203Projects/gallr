import "@supabase/functions-js/edge-runtime.d.ts";
import { createLaunchCheckoutBackend } from "./backend.ts";
import { createLaunchCheckoutHandler } from "./handler.ts";

Deno.serve(createLaunchCheckoutHandler({
  env: (name) => Deno.env.get(name),
  createBackend: createLaunchCheckoutBackend,
}));
