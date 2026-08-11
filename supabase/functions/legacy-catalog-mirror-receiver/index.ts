import { createLegacyCatalogReceiverBackend } from "./backend.ts";
import { createLegacyCatalogMirrorReceiverHandler } from "./handler.ts";

const environmentNames = [
  "SUPABASE_URL",
  "SUPABASE_SECRET_KEYS",
  "SUPABASE_SECRET_KEY",
  "SUPABASE_SERVICE_ROLE_KEY",
] as const;

let backend: ReturnType<typeof createLegacyCatalogReceiverBackend> | undefined;

Deno.serve(
  createLegacyCatalogMirrorReceiverHandler({
    env: (name) => Deno.env.get(name),
    apply: async (payload) => {
      if (!backend) {
        const environment: Record<string, string | undefined> = {};
        for (const name of environmentNames) {
          environment[name] = Deno.env.get(name);
        }
        backend = createLegacyCatalogReceiverBackend(environment);
      }
      return await backend.apply(payload);
    },
  }),
);
