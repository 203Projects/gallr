import "@supabase/functions-js/edge-runtime.d.ts";

import { createImpactBackend } from "./backend.ts";
import { createImpactHandler } from "./handler.ts";

Deno.serve(
  createImpactHandler({
    env: (name) => Deno.env.get(name),
    createBackend: (environment) => createImpactBackend(environment),
  }),
);
