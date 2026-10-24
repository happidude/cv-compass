import { config } from "./config";
export const date = (value: string | null | undefined) =>
  value
    ? new Intl.DateTimeFormat("vi-VN", {
        dateStyle: "short",
        timeStyle: "short",
        timeZone: config.timezone,
      }).format(new Date(value))
    : "—";
export function bytes(value: number) {
  return value >= 1048576
    ? `${new Intl.NumberFormat("vi-VN", { maximumFractionDigits: 1 }).format(value / 1048576)} MiB`
    : `${new Intl.NumberFormat("vi-VN", { maximumFractionDigits: 1 }).format(value / 1024)} KiB`;
}
export const displayName = (value: string) =>
  value.replace(/\p{Cc}|[\u202a-\u202e\u2066-\u2069]/gu, "�");
