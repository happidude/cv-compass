import { z } from "zod";
const number = (fallback: number, min: number, max: number) =>
  z.coerce.number().int().min(min).max(max).default(fallback);
export const configSchema = z.object({
  VITE_API_BASE_URL: z
    .string()
    .default("/api")
    .refine(
      (v) =>
        (v.startsWith("/") && !v.startsWith("//")) ||
        /^https?:\/\/[^\s]+$/.test(v),
      "API base URL không hợp lệ",
    ),
  VITE_MOCK: z.enum(["true", "false"]).default("false"),
  VITE_UPLOAD_MAX_BYTES: number(10485760, 1, 10485760),
  VITE_UPLOAD_MAX_FILES: number(10, 1, 10),
  VITE_POLL_INTERVAL_MS: number(5000, 500, 60000),
  VITE_POLL_MAX_MS: number(120000, 500, 3600000),
  VITE_DISPLAY_TIMEZONE: z
    .string()
    .default("Asia/Ho_Chi_Minh")
    .refine((v) => {
      try {
        new Intl.DateTimeFormat("vi-VN", { timeZone: v });
        return true;
      } catch {
        return false;
      }
    }),
});
export function readConfig(env: Record<string, unknown>, production: boolean) {
  const value = configSchema.parse(env);
  if (production && value.VITE_MOCK === "true")
    throw new Error("Mock không được bật trong production");
  return {
    apiBase: value.VITE_API_BASE_URL.replace(/\/$/, ""),
    mock: value.VITE_MOCK === "true",
    maxBytes: value.VITE_UPLOAD_MAX_BYTES,
    maxFiles: value.VITE_UPLOAD_MAX_FILES,
    pollInterval: value.VITE_POLL_INTERVAL_MS,
    pollMax: value.VITE_POLL_MAX_MS,
    timezone: value.VITE_DISPLAY_TIMEZONE,
  };
}
export const config = readConfig(import.meta.env, import.meta.env.PROD);
