import "@supabase/functions-js/edge-runtime.d.ts";

import { createPromotionBackend } from "./backend.ts";
import { createPromotionHandler } from "./handler.ts";

Deno.serve(createPromotionHandler({
  env: (name) => Deno.env.get(name),
  createBackend: createPromotionBackend,
}));
