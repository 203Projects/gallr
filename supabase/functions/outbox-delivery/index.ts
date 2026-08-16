import { createOutboxDeliveryHandler } from "./handler.ts";
import { createGalleryAlertDispatcher } from "./gallery_alert_runtime.ts";

const runtimeDependencies = {
  env: (name: string) => Deno.env.get(name),
  fetch: (input: string | URL | Request, init?: RequestInit) =>
    fetch(input, init),
};
let galleryAlertDispatcher:
  | ReturnType<typeof createGalleryAlertDispatcher>
  | null = null;

Deno.serve(
  createOutboxDeliveryHandler({
    ...runtimeDependencies,
    galleryAlerts: (event) => {
      galleryAlertDispatcher ??= createGalleryAlertDispatcher(
        runtimeDependencies,
      );
      return galleryAlertDispatcher(event);
    },
  }),
);
