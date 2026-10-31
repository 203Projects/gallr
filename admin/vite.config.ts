import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    fs: {
      // The hours parser test reads the app's opening-hours parity file (spec 101) from this contracts folder;
      // nothing else outside admin/ is served.
      allow: [".", "../specs/089-personal-routes/contracts"],
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: "./src/test-setup.ts",
    globals: true,
    css: true,
  },
});
