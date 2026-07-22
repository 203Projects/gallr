import "@supabase/functions-js/edge-runtime.d.ts";

import { createGeocodeHandler } from "./handler.ts";

Deno.serve(createGeocodeHandler());
