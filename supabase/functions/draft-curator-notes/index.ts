import { createDraftingBackend } from "./backend.ts";
import { createDraftHandler } from "./handler.ts";

Deno.serve(createDraftHandler({
  env: (name) => Deno.env.get(name),
  createBackend: createDraftingBackend,
}));
