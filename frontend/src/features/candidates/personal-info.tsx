import { useState } from "react";
import type { z } from "zod";
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
    <div>
      {personalFields.map((key) => {
        const field = metadata?.fields[key];
        const doc = field?.documentId;
        const canView = !!doc && documents.some((d) => d.id === doc);
        return (
          <div key={key}>
            <p>
              <strong>{labels[key]}:</strong>{" "}
              <span className={key === "summary" ? "raw-text" : undefined}>
                {patch && !Object.hasOwn(basic, key)
                  ? "Giữ nguyên (không có thay đổi đề xuất)"
                  : valueLabel(key, basic[key])}
              </span>
            </p>
            {field && (
              <>
                <p>
                  {field.enabled === false
                    ? "Field đã tắt"
                    : field.state
                      ? parserLabels[field.state]
                      : field.reviewState === "EDITED"
                        ? "Đã chỉnh sửa thủ công"
                        : ""}
                  {field.origin && ` · Nguồn ${field.origin}`}
                </p>
                {field.raw != null && (
                  <p>
                    Giá trị nguồn: <span className="raw-text">{field.raw}</span>
                  </p>
                )}
                {!!field.issues?.length && (
                  <p>Vấn đề: {field.issues.map((i) => i.code).join(", ")}</p>
                )}
                {field.evidence?.map((span, index) =>
                  canView ? (
                    <button key={index} onClick={() => onEvidence(doc, span)}>
                      Đối chiếu {labels[key]} — trang {span.page}, đoạn{" "}
                      {index + 1}
                    </button>
                  ) : (
                    <p key={index}>
                      Nguồn {labels[key]} chưa có tài liệu liên kết để đối
                      chiếu.
                    </p>
                  ),
                )}
                {!!field.alternatives?.length && (
                  <details>
                    <summary>Phương án khác — {labels[key]}</summary>
                    {field.alternatives.map((alternative, index) => (
                      <div key={index}>
                        <p className="raw-text">{alternative.raw}</p>
                        {alternative.normalized && (
                          <p>Chuẩn hóa: {alternative.normalized}</p>
                        )}
                        {alternative.evidence.map((span, n) =>
                          canView ? (
                            <button
                              key={n}
                              onClick={() => onEvidence(doc, span)}
                            >
                              Đối chiếu phương án {labels[key]} {index + 1} —
                              trang {span.page}, đoạn {n + 1}
                            </button>
                          ) : (
                            <p key={n}>
                              Chưa có tài liệu liên kết để đối chiếu phương án.
                            </p>
                          ),
                        )}
                      </div>
                    ))}
                  </details>
                )}
              </>
            )}
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
      className="panel edit-form"
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
      {personalFields.map((key) => (
        <label key={key}>
          {labels[key]}
          {key === "summary" ? (
            <textarea
              value={data[key] ?? ""}
              maxLength={10000}
              onChange={(event) => update(key, event.target.value)}
            />
          ) : (
            <input
              value={data[key] ?? ""}
              maxLength={key === "phone" ? 50 : 255}
              onChange={(event) => update(key, event.target.value)}
            />
          )}
        </label>
      ))}
      <p>
        Chỉ field đã sửa được gửi. Xóa hết nội dung một field là yêu cầu xóa rõ
        ràng; field khác được giữ nguyên.
      </p>
      {failed && (
        <p role="alert">
          Chưa lưu được thông tin. Kiểm tra dữ liệu hoặc tải lại khi xung đột.
        </p>
      )}
      <button type="submit" disabled={busy}>
        Lưu thông tin cơ bản
      </button>{" "}
      <button type="button" disabled={busy} onClick={onCancel}>
        Hủy
      </button>
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
    <article className="panel">
      <h3>Đề xuất thông tin cá nhân từ tài liệu #{proposal.documentId}</h3>
      <h4>Giá trị hiện hành</h4>
      <PersonalFields
        basic={basic}
        documents={documents}
        onEvidence={onEvidence}
      />
      <h4>Giá trị đề xuất</h4>
      <PersonalFields
        patch
        basic={proposal.data.patch}
        metadata={proposal.data.metadata}
        documents={documents}
        onEvidence={onEvidence}
      />
      <button
        disabled={saving}
        onClick={() => {
          void onDecision("ACCEPT").catch(() => {});
        }}
      >
        Chấp nhận đề xuất
      </button>{" "}
      <button disabled={saving} onClick={() => setEditing(true)}>
        Sửa đề xuất rồi lưu
      </button>{" "}
      <button
        disabled={saving}
        onClick={() => {
          void onDecision("REJECT").catch(() => {});
        }}
      >
        Từ chối đề xuất
      </button>
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
    </article>
  );
}
