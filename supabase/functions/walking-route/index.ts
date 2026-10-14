import { createWalkingRouteBackend } from "./backend.ts";
import { createWalkingRouteHandler } from "./handler.ts";
import { createWalkingProvider } from "./providers/provider.ts";

Deno.serve(createWalkingRouteHandler({
  env: (name) => Deno.env.get(name),
  createBackend: createWalkingRouteBackend,
  createProvider: (environment) => createWalkingProvider(environment),
}));
