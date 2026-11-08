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
export const periodStateLabel = (state: string) =>
  state === "PROPOSED"
    ? "Đề xuất"
    : state === "AMBIGUOUS"
      ? "Ngày mâu thuẫn"
      : state === "INVALID"
        ? "Ngày không hợp lệ"
        : "Chưa tìm thấy ngày";
