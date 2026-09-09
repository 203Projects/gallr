import { createMobileAnalyticsBackend } from "./backend.ts";
import { createMobileAnalyticsHandler } from "./handler.ts";

Deno.serve(createMobileAnalyticsHandler({
  env: (name) => Deno.env.get(name),
  createBackend: createMobileAnalyticsBackend,
}));
