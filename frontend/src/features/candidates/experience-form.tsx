import { useState } from "react";
import { Button } from "../../shared/ui";
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
  supportsExperienceTotal = false,
  onSave,
  onCancel,
}: {
  initial?: ExperienceData;
  supportsExperienceTotal?: boolean;
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
  const [countInTotal, setCountInTotal] = useState(
    initial?.countInTotal ?? true,
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
      className="form-card"
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
            ...(supportsExperienceTotal ? { countInTotal } : {}),
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
      <div className="form-grid">
        <label className="field">
          Công ty
          <input
            className="input"
            required
            maxLength={4096}
            value={company}
            onChange={(e) => setCompany(e.target.value)}
          />
        </label>
        <div className="field">
          <label className="field">
            Chức danh
            <input
              className="input"
              maxLength={4096}
              value={title}
              onChange={(e) => setTitle(e.target.value)}
            />
          </label>
          <span className="field__hint">
            Có thể để trống chức danh nếu chưa xác định.
          </span>
        </div>
        <p className="form-note span-2">
          Ngày theo YYYY, YYYY-MM hoặc YYYY-MM-DD; giữ đúng độ chính xác được
          biết.
        </p>
        <label className="field">
          Bắt đầu
          <input
            className="input"
            value={start}
            placeholder="YYYY-MM"
            onChange={(e) => setStart(e.target.value)}
          />
        </label>
        <label className="field">
          Kết thúc
          <input
            className="input"
            disabled={ongoing}
            value={end}
            onChange={(e) => setEnd(e.target.value)}
          />
        </label>
        <label className="checkbox span-2">
          <input
            type="checkbox"
            checked={ongoing}
            onChange={(e) => setOngoing(e.target.checked)}
          />
          Đang làm theo nội dung CV
        </label>
        {supportsExperienceTotal && (
          <label className="checkbox span-2">
            <input
              type="checkbox"
              checked={countInTotal}
              onChange={(e) => setCountInTotal(e.target.checked)}
            />
            Tính vào tổng kinh nghiệm
          </label>
        )}
        <label className="field">
          Mã chuyên môn (phân cách bằng dấu phẩy)
          <input
            className="input"
            value={functions}
            onChange={(e) => setFunctions(e.target.value)}
          />
        </label>
        <label className="field">
          Mã cấp bậc
          <input
            className="input"
            value={seniority}
            onChange={(e) => setSeniority(e.target.value)}
          />
        </label>
        <label className="field span-2">
          Mã ngành doanh nghiệp (riêng với chuyên môn)
          <input
            className="input"
            value={industries}
            onChange={(e) => setIndustries(e.target.value)}
          />
        </label>
        <label className="field span-2">
          Mô tả
          <textarea
            className="textarea"
            maxLength={16384}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
          />
        </label>
      </div>
      <p className="form-note">
        Giá trị chỉnh sửa mang nguồn USER; bằng chứng cũ được giữ trong lịch sử.
      </p>
      {error && (
        <p role="alert" className="form-error">
          {error}
        </p>
      )}
      <div className="btn-row">
        <Button disabled={busy} type="submit">
          Lưu kinh nghiệm
        </Button>
        <Button variant="secondary" onClick={onCancel}>
          Hủy
        </Button>
      </div>
    </form>
  );
}
