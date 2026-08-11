import { createOutboxDeliveryHandler } from "./handler.ts";

Deno.serve(
  createOutboxDeliveryHandler({
    env: (name) => Deno.env.get(name),
    fetch: (input, init) => fetch(input, init),
  }),
);
