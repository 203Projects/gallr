import { createGalleryAlertEnrollmentBackend } from "./backend.ts";
import { createGalleryAlertEnrollmentHandler } from "./handler.ts";

Deno.serve(createGalleryAlertEnrollmentHandler({
  env: (name) => Deno.env.get(name),
  createBackend: createGalleryAlertEnrollmentBackend,
}));
