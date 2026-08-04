import "@supabase/functions-js/edge-runtime.d.ts";

import { createLegacyCatalogMirrorBackend } from "./backend.ts";
import { createLegacyCatalogMirrorHandler } from "./handler.ts";

const environmentNames = [
  "SUPABASE_URL",
  "SUPABASE_SECRET_KEYS",
  "SUPABASE_SECRET_KEY",
  "SUPABASE_SERVICE_ROLE_KEY",
  "LEGACY_CATALOG_RECEIVER_URL",
  "LEGACY_CATALOG_RECEIVER_TOKEN",
  "LEGACY_CATALOG_MIRROR_REASON",
] as const;

let backend: ReturnType<typeof createLegacyCatalogMirrorBackend> | undefined;

Deno.serve(
  createLegacyCatalogMirrorHandler({
    env: (name) => Deno.env.get(name),
    mirror: async (source) => {
      if (!backend) {
        const environment: Record<string, string | undefined> = {};
        for (const name of environmentNames) {
          environment[name] = Deno.env.get(name);
        }
        backend = createLegacyCatalogMirrorBackend(environment);
      }
      await backend.mirror(source);
    },
  }),
);
