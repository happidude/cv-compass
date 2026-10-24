import { defineConfig } from "vitest/config";
import { loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
export default defineConfig(({ mode, command }) => {
  const env = loadEnv(mode, process.cwd(), "");
  if (command === "build" && env.VITE_MOCK === "true")
    throw new Error("Mock không được bật trong production");
  const target = env.API_PROXY_TARGET;
  if (
    command === "serve" &&
    mode !== "test" &&
    env.VITE_MOCK !== "true" &&
    !target
  )
    throw new Error("API_PROXY_TARGET is required when VITE_MOCK=false");
  if (target) {
    const origin = new URL(target);
    if (
      !["http:", "https:"].includes(origin.protocol) ||
      origin.username ||
      origin.password ||
      origin.pathname !== "/" ||
      origin.search ||
      origin.hash
    )
      throw new Error(
        "API_PROXY_TARGET must be a backend origin without credentials or path",
      );
  }
  return {
    plugins: [react(), tailwindcss()],
    publicDir: command === "build" ? false : "dev-public",
    server: {
      strictPort: true,
      proxy: target
        ? {
            "/api": {
              target,
              changeOrigin: true,
            },
          }
        : undefined,
    },
    test: {
      environment: "jsdom",
      setupFiles: ["./tests/setup.ts"],
      include: ["tests/**/*.test.{ts,tsx}"],
      restoreMocks: true,
    },
  };
});
