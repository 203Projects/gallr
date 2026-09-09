import { createEditorInviteBackend } from "./backend.ts";
import { createInviteEditorHandler } from "./handler.ts";

Deno.serve(createInviteEditorHandler({
  env: (name) => Deno.env.get(name),
  createBackend: createEditorInviteBackend,
}));
