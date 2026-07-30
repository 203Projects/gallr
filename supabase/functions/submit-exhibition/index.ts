import "@supabase/functions-js/edge-runtime.d.ts";

import { createSubmissionBackend } from "./backend.ts";
import { createSubmitExhibitionHandler } from "./handler.ts";

Deno.serve(
  createSubmitExhibitionHandler({
    env: (name) => Deno.env.get(name),
    createBackend: (environment) => createSubmissionBackend(environment),
  }),
);
