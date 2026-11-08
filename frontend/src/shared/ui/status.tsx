import { Badge, type Tone } from "./badge";
const job: Record<string, [string, Tone, string]> = {
  NOT_SCHEDULED: [
    "Chưa lên lịch OCR",
    "neutral",
    "CV đã lưu, chưa có job OCR.",
  ],
  PENDING: ["Chờ xử lý", "neutral", "Job đang chờ được xử lý."],
  PROCESSING: ["Đang xử lý", "info", "Job OCR đang chạy."],
  COMPLETED: ["Hoàn tất", "good", "Job OCR đã hoàn tất."],
  PARTIAL: ["Một phần", "warn", "Job OCR chỉ hoàn tất một phần."],
  FAILED: ["Thất bại", "bad", "Job OCR thất bại."],
};
const batch: Record<string, [string, Tone, string]> = {
  PENDING: ["Chờ nhập", "neutral", "Batch đang chờ nhập."],
  PROCESSING: ["Đang nhập", "info", "Đang lưu file."],
  COMPLETED: ["Hoàn tất", "good", "Upload hoàn tất."],
  PARTIAL_FAILED: ["Một phần thất bại", "warn", "Có file upload thất bại."],
  FAILED: ["Thất bại", "bad", "Upload thất bại."],
};
const page: Record<string, [string, Tone, string]> = {
  ok: ["Có text", "good", "Trang có text."],
  blank: ["Trang trống", "neutral", "Trang trống, không có text."],
  failed: ["Thất bại", "bad", "OCR thất bại ở trang này."],
  not_processed: ["Chưa xử lý", "neutral", "Chưa xử lý. Chưa có kết quả OCR."],
};
const result: Record<string, [string, Tone, string]> = {
  success: ["Thành công", "good", "Kết quả OCR thành công."],
  partial: ["Một phần", "warn", "Kết quả OCR một phần."],
  failed: ["Thất bại", "bad", "Kết quả OCR thất bại."],
};
export type StatusDomain = "job" | "batch" | "page" | "result";
export function statusInfo(
  domain: StatusDomain,
  value: string,
): [string, Tone, string] {
  const mapping = { job, batch, page, result }[domain];
  return Object.hasOwn(mapping, value)
    ? mapping[value]
    : ["Không xác định", "neutral", "Trạng thái chưa được ứng dụng hỗ trợ."];
}
export function Status({
  domain,
  value,
}: {
  domain: StatusDomain;
  value: string;
}) {
  const [label, tone, description] = statusInfo(domain, value);
  return (
    <Badge tone={tone} mark title={description}>
      {label}
    </Badge>
  );
}
