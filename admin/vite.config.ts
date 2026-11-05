import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    fs: {
      // The hours parser and term suggestion tests read the app's parity files (spec 101) from these contracts folders;
      // nothing else outside admin/ is served.
      allow: [".", "../specs/089-personal-routes/contracts", "../specs/101-taste-listening/contracts"],
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: "./src/test-setup.ts",
    globals: true,
    css: true,
  },
});
