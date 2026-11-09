import type { Tone } from "../../shared/ui";
import type { ExperienceData } from "./schema";
/** Review state of a profile section (basic info or one experience). */
export const stateLabel = (state: string) =>
  state === "CONFIRMED"
    ? "Đã xác nhận"
    : state === "EDITED"
      ? "Đã chỉnh sửa"
      : "Chưa xác minh";
/** Display name: parsed name, else the source file name, else a neutral id label. */
export const candidateTitle = (
  id: string | number,
  fullName?: string | null,
  filename?: string | null,
) => fullName || filename || "Ứng viên #" + id;
/** The earliest linked document (lowest id), matching the list API fallback. */
export const firstFilename = (
  documents: readonly { id: string; filename: string }[],
) =>
  documents.reduce<{ id: string; filename: string } | undefined>(
    (first, doc) => (!first || Number(doc.id) < Number(first.id) ? doc : first),
    undefined,
  )?.filename;
export const stateTone = (state: string): Tone =>
  state === "CONFIRMED" ? "good" : state === "EDITED" ? "info" : "warn";
export function dateLabel(value: ExperienceData["period"]["start"]) {
  if (!value) return "Chưa tìm thấy";
  if (value.precision === "YEAR") return value.year + " (năm)";
  if (value.precision === "MONTH")
    return String(value.month).padStart(2, "0") + "/" + value.year + " (tháng)";
  return (
    String(value.day).padStart(2, "0") +
    "/" +
    String(value.month).padStart(2, "0") +
    "/" +
    value.year +
    " (ngày)"
  );
}
export function formatExperienceMonths(months: number) {
  return months === 0
    ? "Chưa tính được"
    : `${Math.floor(months / 12)} năm ${months % 12} tháng`;
}

export function experienceSkipReasonLabel(reason: string) {
  switch (reason) {
    case "MISSING_START":
      return "Thiếu ngày bắt đầu";
    case "MISSING_END":
      return "Thiếu ngày kết thúc";
    case "PERIOD_UNRESOLVED":
      return "Kỳ kinh nghiệm chưa xác định";
    case "EXCLUDED_BY_USER":
      return "Đã bỏ khỏi tổng";
    case "NO_REFERENCE_DATE":
      return "Chưa có ngày nhập CV để tham chiếu";
    case "START_AFTER_REFERENCE":
      return "Ngày bắt đầu sau ngày nhập CV";
    default:
      return "Lý do chưa xác định";
  }
}

export const periodStateLabel = (state: string) =>
  state === "PROPOSED"
    ? "Đề xuất"
    : state === "AMBIGUOUS"
      ? "Ngày mâu thuẫn"
      : state === "INVALID"
        ? "Ngày không hợp lệ"
        : "Chưa tìm thấy ngày";
