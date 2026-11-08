import { statusInfo, type RouteState, type RouteStop } from "../../shared/ui";
const ocrState: Record<string, RouteState> = {
  NOT_SCHEDULED: "todo",
  PENDING: "waiting",
  PROCESSING: "active",
  COMPLETED: "done",
  PARTIAL: "attention",
  FAILED: "failed",
};
/**
 * Route of one stored CV. The documents API reports storage and OCR only, so
 * the profile stop is always "unknown" until an API exposes mapping state.
 */
export function documentRoute(ocrStatus: string): RouteStop[] {
  return [
    { label: "Lưu file", state: "done", detail: "Đã lưu PDF gốc" },
    {
      label: "OCR",
      state: ocrState[ocrStatus] ?? "unknown",
      detail: statusInfo("job", ocrStatus)[0],
    },
    { label: "Hồ sơ", state: "unknown", detail: "Chưa có dữ liệu trạng thái" },
  ];
}
/** OCR outcomes that need a person to look at the CV. */
export const needsAttention = (ocrStatus: string) =>
  ocrStatus === "FAILED" || ocrStatus === "PARTIAL";
export const inProgress = (ocrStatus: string) =>
  ocrStatus === "PENDING" || ocrStatus === "PROCESSING";
