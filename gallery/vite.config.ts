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
    setupFiles: "./src/test-setup.ts",
    globals: true,
    css: true,
  },
});
