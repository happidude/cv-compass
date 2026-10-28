import { useState } from "react";
import type { ExperienceData } from "./schema";
function dateText(value: ExperienceData["period"]["start"]) {
  if (!value) return "";
  return (
    String(value.year) +
    (value.month ? "-" + String(value.month).padStart(2, "0") : "") +
    (value.day ? "-" + String(value.day).padStart(2, "0") : "")
  );
}
function partial(value: string) {
  if (!value.trim()) return null;
  if (!/^\d{4}(-\d{2})?(-\d{2})?$/.test(value)) throw new Error("date");
  const [year, month, day] = value.split("-").map(Number);
  return {
    year,
    month: month ?? null,
    day: day ?? null,
    precision: day ? "DAY" : month ? "MONTH" : "YEAR",
  };
}
const codes = (value: string) =>
  value
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
export function ExperienceForm({
  initial,
  onSave,
  onCancel,
}: {
  initial?: ExperienceData;
  onSave: (data: unknown) => Promise<void>;
  onCancel: () => void;
}) {
  const [company, setCompany] = useState(initial?.company.raw ?? "");
  const [title, setTitle] = useState(initial?.title.raw ?? "");
  const [start, setStart] = useState(dateText(initial?.period.start));
  const [end, setEnd] = useState(dateText(initial?.period.end));
  const [ongoing, setOngoing] = useState(
    initial?.period.ongoingAsStated ?? false,
  );
  const [functions, setFunctions] = useState(
    initial?.title.functionCodes?.join(", ") ?? "",
  );
  const [industries, setIndustries] = useState(
    initial?.company.industryCodes?.join(", ") ?? "",
  );
  const [seniority, setSeniority] = useState(
    initial?.title.seniorityCode ?? "",
  );
  const [description, setDescription] = useState(initial?.descriptionRaw ?? "");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  return (
    <form
      className="panel edit-form"
      onSubmit={async (e) => {
        e.preventDefault();
        setError("");
        setBusy(true);
        try {
          await onSave({
            company: { raw: company, industryCodes: codes(industries) },
            title: {
              raw: title || null,
              functionCodes: codes(functions),
              seniorityCode: seniority || null,
            },
            period: {
              start: partial(start),
              end: ongoing ? null : partial(end),
              ongoingAsStated: ongoing,
              state: "PROPOSED",
            },
            descriptionRaw: description,
          });
        } catch {
          setError(
            "Chưa lưu được. Kiểm tra ngày, mã phân loại và thông báo xung đột phiên bản.",
          );
        } finally {
          setBusy(false);
        }
      }}
    >
      <h3>{initial ? "Chỉnh sửa kinh nghiệm" : "Thêm kinh nghiệm"}</h3>
      <label>
        Công ty
        <input
          required
          maxLength={4096}
          value={company}
          onChange={(e) => setCompany(e.target.value)}
        />
      </label>
      <label>
        Chức danh
        <input
          maxLength={4096}
          value={title}
          onChange={(e) => setTitle(e.target.value)}
        />
      </label>
      <p className="footnote">Có thể để trống chức danh nếu chưa xác định.</p>
      <p>
        Ngày theo YYYY, YYYY-MM hoặc YYYY-MM-DD; giữ đúng độ chính xác được
        biết.
      </p>
      <label>
        Bắt đầu
        <input
          value={start}
          placeholder="YYYY-MM"
          onChange={(e) => setStart(e.target.value)}
        />
      </label>
      <label>
        Kết thúc
        <input
          disabled={ongoing}
          value={end}
          onChange={(e) => setEnd(e.target.value)}
        />
      </label>
      <label>
        <input
          type="checkbox"
          checked={ongoing}
          onChange={(e) => setOngoing(e.target.checked)}
        />
        Đang làm theo nội dung CV
      </label>
      <label>
        Mã chuyên môn (phân cách bằng dấu phẩy)
        <input
          value={functions}
          onChange={(e) => setFunctions(e.target.value)}
        />
      </label>
      <label>
        Mã cấp bậc
        <input
          value={seniority}
          onChange={(e) => setSeniority(e.target.value)}
        />
      </label>
      <label>
        Mã ngành doanh nghiệp (riêng với chuyên môn)
        <input
          value={industries}
          onChange={(e) => setIndustries(e.target.value)}
        />
      </label>
      <label>
        Mô tả
        <textarea
          maxLength={16384}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
        />
      </label>
      <p>
        Giá trị chỉnh sửa mang nguồn USER; bằng chứng cũ được giữ trong lịch sử.
      </p>
      {error && <p role="alert">{error}</p>}
      <button disabled={busy} type="submit">
        Lưu kinh nghiệm
      </button>{" "}
      <button type="button" onClick={onCancel}>
        Hủy
      </button>
    </form>
  );
}
