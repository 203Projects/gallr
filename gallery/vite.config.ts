import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

const repositoryRoot = new URL("..", import.meta.url).pathname;

export default defineConfig({
  plugins: [react()],
  server: {
    fs: {
      allow: [repositoryRoot],
    },
  },
  test: {
    environment: "jsdom",
    environmentOptions: {
      jsdom: {
        url: "https://gallery.test",
      },
    },
    setupFiles: "./src/test-setup.ts",
    globals: true,
    css: true,
  },
});
