import { useState } from "react";
import type { z } from "zod";
import { Badge, Button } from "../../shared/ui";
import { EvidenceButton } from "./evidence";
import type { Basic, PersonalInfo, Proposal, evidenceSchema } from "./schema";
export const personalFields = [
  "fullName",
  "email",
  "phone",
  "location",
  "summary",
  "gender",
] as const;
const labels: Record<(typeof personalFields)[number], string> = {
  fullName: "Họ tên",
  email: "Email",
  phone: "Điện thoại",
  location: "Địa điểm",
  summary: "Tóm tắt",
  gender: "Giới tính",
};
type Evidence = z.infer<typeof evidenceSchema>;
const parserLabels: Record<string, string> = {
  PROPOSED: "Đề xuất",
  NOT_FOUND: "Chưa tìm thấy",
  INVALID: "Không hợp lệ",
  AMBIGUOUS: "Mơ hồ",
};
const parserTone = (state?: string) =>
  state === "PROPOSED"
    ? "info"
    : state === "INVALID" || state === "AMBIGUOUS"
      ? "warn"
      : "neutral";
function valueLabel(key: string, value: string | null | undefined) {
  return key === "gender" && value === "MALE"
    ? "Nam"
    : key === "gender" && value === "FEMALE"
      ? "Nữ"
      : value || "Chưa có";
}
export function PersonalFields({
  basic,
  metadata,
  documents,
  onEvidence,
  patch = false,
}: {
  basic: Basic;
  metadata?: PersonalInfo;
  patch?: boolean;
  documents: readonly { id: string }[];
  onEvidence: (doc: string, span: Evidence) => void;
}) {
  return (
    <div className="field-list">
      {personalFields.map((key) => {
        const field = metadata?.fields[key];
        const doc = field?.documentId;
        const canView = !!doc && documents.some((d) => d.id === doc);
        const kept = patch && !Object.hasOwn(basic, key);
        const empty = !kept && !basic[key];
        const status =
          field?.enabled === false
            ? "Field đã tắt"
            : field?.state
              ? parserLabels[field.state]
              : field?.reviewState === "EDITED"
                ? "Đã chỉnh sửa thủ công"
                : "";
        return (
          <div className="field-row" key={key}>
            <p className="field-row__label">
              <strong>{labels[key]}:</strong>
            </p>
            <div className="field-row__body">
              <p
                className={
                  "field-row__value" +
                  (key === "summary" ? " is-multiline" : "") +
                  (empty || kept ? " is-empty" : "")
                }
              >
                <span>
                  {kept
                    ? "Giữ nguyên (không có thay đổi đề xuất)"
                    : valueLabel(key, basic[key])}
                </span>
              </p>
              {field && (
                <div className="field-row__meta">
                  {(status || field.origin) && (
                    <Badge tone={parserTone(field.state)}>
                      {status}
                      {field.origin &&
                        `${status ? " · " : ""}Nguồn ${field.origin}`}
                    </Badge>
                  )}
                  {field.raw != null && (
                    <p className="field-row__raw">
                      Giá trị nguồn: <span>{field.raw}</span>
                    </p>
                  )}
                  {!!field.issues?.length && (
                    <p className="field-row__issue">
                      Vấn đề: {field.issues.map((i) => i.code).join(", ")}
                    </p>
                  )}
                  {field.evidence?.map((span, index) =>
                    canView ? (
                      <EvidenceButton
                        key={index}
                        page={span.page}
                        index={index + 1}
                        label={`Đối chiếu ${labels[key]} — trang ${span.page}, đoạn ${index + 1}`}
                        onClick={() => onEvidence(doc, span)}
                      />
                    ) : (
                      <p key={index} className="field-row__issue">
                        Nguồn {labels[key]} chưa có tài liệu liên kết để đối
                        chiếu.
                      </p>
                    ),
                  )}
                  {!!field.alternatives?.length && (
                    <details className="disclosure field-row__alts">
                      <summary>Phương án khác — {labels[key]}</summary>
                      {field.alternatives.map((alternative, index) => (
                        <div className="alt" key={index}>
                          <p className="alt__raw">{alternative.raw}</p>
                          {alternative.normalized && (
                            <p className="muted">
                              Chuẩn hóa: {alternative.normalized}
                            </p>
                          )}
                          {alternative.evidence.map((span, n) =>
                            canView ? (
                              <EvidenceButton
                                key={n}
                                page={span.page}
                                index={n + 1}
                                label={`Đối chiếu phương án ${labels[key]} ${index + 1} — trang ${span.page}, đoạn ${n + 1}`}
                                onClick={() => onEvidence(doc, span)}
                              />
                            ) : (
                              <p key={n} className="field-row__issue">
                                Chưa có tài liệu liên kết để đối chiếu phương
                                án.
                              </p>
                            ),
                          )}
                        </div>
                      ))}
                    </details>
                  )}
                </div>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
export function PersonalForm({
  initial,
  onSave,
  onCancel,
}: {
  initial: Basic;
  onSave: (patch: Basic) => Promise<void>;
  onCancel: () => void;
}) {
  const [data, setData] = useState(initial);
  const [dirty, setDirty] = useState<Basic>({});
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  function update(key: (typeof personalFields)[number], value: string) {
    setData({ ...data, [key]: value });
    setDirty({ ...dirty, [key]: value === "" ? null : value });
  }
  return (
    <form
      className="form-card"
      onSubmit={async (event) => {
        event.preventDefault();
        setFailed(false);
        setBusy(true);
        try {
          await onSave(dirty);
        } catch {
          setFailed(true);
        } finally {
          setBusy(false);
        }
      }}
    >
      <div className="form-grid">
        {personalFields.map((key) => (
          <label
            key={key}
            className={"field" + (key === "summary" ? " span-2" : "")}
          >
            {labels[key]}
            {key === "summary" ? (
              <textarea
                className="textarea"
                value={data[key] ?? ""}
                maxLength={10000}
                onChange={(event) => update(key, event.target.value)}
              />
            ) : (
              <input
                className="input"
                value={data[key] ?? ""}
                maxLength={key === "phone" ? 50 : 255}
                onChange={(event) => update(key, event.target.value)}
              />
            )}
          </label>
        ))}
      </div>
      <p className="form-note">
        Chỉ field đã sửa được gửi. Xóa hết nội dung một field là yêu cầu xóa rõ
        ràng; field khác được giữ nguyên.
      </p>
      {failed && (
        <p role="alert" className="form-error">
          Chưa lưu được thông tin. Kiểm tra dữ liệu hoặc tải lại khi xung đột.
        </p>
      )}
      <div className="btn-row">
        <Button type="submit" disabled={busy}>
          Lưu thông tin cơ bản
        </Button>
        <Button variant="secondary" disabled={busy} onClick={onCancel}>
          Hủy
        </Button>
      </div>
    </form>
  );
}
export function PersonalProposal({
  proposal,
  basic,
  documents,
  saving,
  onDecision,
  onEvidence,
}: {
  proposal: Extract<Proposal, { kind: "PERSONAL_INFO" }>;
  basic: Basic;
  documents: readonly { id: string }[];
  saving: boolean;
  onDecision: (
    decision: "ACCEPT" | "EDIT" | "REJECT",
    edited?: Basic,
  ) => Promise<void>;
  onEvidence: (doc: string, span: Evidence) => void;
}) {
  const [editing, setEditing] = useState(false);
  return (
    <section className="proposal">
      <h3>Đề xuất thông tin cá nhân từ tài liệu #{proposal.documentId}</h3>
      <div className="compare">
        <div className="compare__side">
          <h4>Giá trị hiện hành</h4>
          <PersonalFields
            basic={basic}
            documents={documents}
            onEvidence={onEvidence}
          />
        </div>
        <div className="compare__side compare__side--proposed">
          <h4>Giá trị đề xuất</h4>
          <PersonalFields
            patch
            basic={proposal.data.patch}
            metadata={proposal.data.metadata}
            documents={documents}
            onEvidence={onEvidence}
          />
        </div>
      </div>
      <div className="btn-row">
        <Button
          disabled={saving}
          onClick={() => {
            void onDecision("ACCEPT").catch(() => {});
          }}
        >
          Chấp nhận đề xuất
        </Button>
        <Button
          variant="secondary"
          disabled={saving}
          onClick={() => setEditing(true)}
        >
          Sửa đề xuất rồi lưu
        </Button>
        <Button
          variant="danger"
          disabled={saving}
          onClick={() => {
            void onDecision("REJECT").catch(() => {});
          }}
        >
          Từ chối đề xuất
        </Button>
      </div>
      {editing && (
        <PersonalForm
          initial={{ ...basic, ...proposal.data.patch }}
          onSave={async (patch) => {
            await onDecision("EDIT", { ...proposal.data.patch, ...patch });
            setEditing(false);
          }}
          onCancel={() => setEditing(false)}
        />
      )}
    </section>
  );
}
