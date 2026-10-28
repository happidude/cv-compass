import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { get, RequestError, errorMessage } from "../../shared/http";
import { change } from "../../shared/session";
import { validRouteId } from "../../shared/schema";
import {
  pageSchema,
  profileSchema,
  proposalsSchema,
  historySchema,
  type ExperienceData,
  type Profile,
} from "./schema";
import { PdfPane } from "./pdf-pane";
import { ExperienceForm } from "./experience-form";

const stateLabel = (state: string) =>
  state === "CONFIRMED"
    ? "Đã xác nhận"
    : state === "EDITED"
      ? "Đã chỉnh sửa"
      : "Chưa xác minh";
export function CandidatesPage() {
  const [before, setBefore] = useState<string>();
  const query = useQuery({
    queryKey: ["candidates", before],
    queryFn: ({ signal }) =>
      get(
        "/candidates" + (before ? "?beforeId=" + before : ""),
        pageSchema,
        signal,
      ),
  });
  return (
    <div className="candidate-screen">
      <h1>Hồ sơ ứng viên</h1>
      <p>
        Hồ sơ xuất hiện ngay sau publication, có thể còn chưa xác minh. Không tự
        gộp theo tên/email/điện thoại.
      </p>
      {query.isPending ? (
        <p role="status">Đang tải dữ liệu…</p>
      ) : query.error ? (
        <p role="alert">
          {errorMessage(query.error)}{" "}
          <button onClick={() => void query.refetch()}>Thử lại</button>
        </p>
      ) : (
        <>
          {query.data.content.length === 0 ? (
            <p>Chưa có hồ sơ ứng viên.</p>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>Ứng viên</th>
                  <th>Thông tin cơ bản</th>
                  <th>Ngày nhập hệ thống</th>
                </tr>
              </thead>
              <tbody>
                {query.data.content.map((row) => (
                  <tr key={row.id}>
                    <td>
                      <Link to={"/candidates/" + row.id}>
                        {row.fullName || "Ứng viên #" + row.id}
                      </Link>
                    </td>
                    <td>{stateLabel(row.basicState)}</td>
                    <td>{new Date(row.importedAt).toLocaleString("vi-VN")}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <button disabled={!before} onClick={() => setBefore(undefined)}>
            Về đầu
          </button>{" "}
          <button
            disabled={!query.data.hasNext}
            onClick={() => setBefore(query.data.nextBeforeId ?? undefined)}
          >
            Trang tiếp
          </button>
        </>
      )}
    </div>
  );
}
type Evidence = NonNullable<ExperienceData["evidence"]>[number];
function dateLabel(value: ExperienceData["period"]["start"]) {
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
function Values({
  data,
  documentId,
  onEvidence,
}: {
  data: ExperienceData;
  documentId: string | null;
  onEvidence: (doc: string, evidence: Evidence) => void;
}) {
  return (
    <div>
      <p>
        <strong>{data.company.raw || "Công ty chưa xác định"}</strong> —{" "}
        {data.title.raw || "Chức danh chưa xác định"}
      </p>
      <p>
        {dateLabel(data.period.start)} →{" "}
        {data.period.ongoingAsStated
          ? "Đang làm theo CV"
          : dateLabel(data.period.end)}{" "}
        ·{" "}
        {data.period.state === "PROPOSED"
          ? "Đề xuất"
          : data.period.state === "AMBIGUOUS"
            ? "Ngày mâu thuẫn"
            : data.period.state === "INVALID"
              ? "Ngày không hợp lệ"
              : "Chưa tìm thấy ngày"}
      </p>
      <p>
        Chuyên môn: {data.title.functionCodes?.join(", ") || "Chưa ánh xạ"} ·
        Cấp bậc: {data.title.seniorityCode || "Chưa ánh xạ"}
      </p>
      <p>
        Ngành doanh nghiệp:{" "}
        {data.company.industryCodes?.join(", ") || "Chưa ánh xạ"}{" "}
        {data.company.industryBasis && " — " + data.company.industryBasis}
      </p>
      <pre className="raw-text">{data.descriptionRaw}</pre>
      {data.issues && data.issues.length > 0 && (
        <p>Vấn đề: {data.issues.map((issue) => issue.code).join(", ")}</p>
      )}
      <details>
        <summary>Phương án khác và bằng chứng nguồn</summary>
        <pre className="raw-text">{JSON.stringify(data, null, 2)}</pre>
      </details>
      {documentId &&
        data.evidence?.map((span, index) => (
          <button key={index} onClick={() => onEvidence(documentId, span)}>
            Đối chiếu trang {span.page}, đoạn {index + 1}
          </button>
        ))}
    </div>
  );
}
function BasicForm({
  profile,
  onSave,
  onCancel,
}: {
  profile: Profile;
  onSave: (data: unknown) => Promise<void>;
  onCancel: () => void;
}) {
  const [data, setData] = useState(profile.basic);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(false);
  return (
    <form
      className="panel edit-form"
      onSubmit={async (event) => {
        event.preventDefault();
        setBusy(true);
        try {
          await onSave(data);
        } catch {
          setError(true);
        } finally {
          setBusy(false);
        }
      }}
    >
      {(["fullName", "email", "phone", "location", "summary"] as const).map(
        (key, index) => (
          <label key={key}>
            {["Họ tên", "Email", "Điện thoại", "Địa điểm", "Tóm tắt"][index]}
            <input
              value={data[key] ?? ""}
              maxLength={key === "summary" ? 10000 : key === "phone" ? 50 : 255}
              onChange={(e) => setData({ ...data, [key]: e.target.value })}
            />
          </label>
        ),
      )}
      {error && (
        <p role="alert">
          Chưa lưu được thông tin. Kiểm tra dữ liệu hoặc tải lại khi xung đột.
        </p>
      )}
      <button disabled={busy} type="submit">
        Lưu thông tin cơ bản
      </button>{" "}
      <button type="button" onClick={onCancel}>
        Hủy
      </button>
    </form>
  );
}
export function CandidatePage() {
  const { id = "" } = useParams();
  return validRouteId(id) ? (
    <CandidateContent key={id} id={id} />
  ) : (
    <p>ID ứng viên không hợp lệ.</p>
  );
}
function CandidateContent({ id }: { id: string }) {
  const [linkDocumentId, setLinkDocumentId] = useState("");
  const client = useQueryClient();
  const [document, setDocument] = useState<string>();
  const [page, setPage] = useState(1);
  const [quote, setQuote] = useState("");
  const [basicEdit, setBasicEdit] = useState(false);
  const [editing, setEditing] = useState<string>();
  const [proposalEditing, setProposalEditing] = useState<string>();
  const [targets, setTargets] = useState<Record<string, string>>({});
  const [historyBefore, setHistoryBefore] = useState<string>();
  const [error, setError] = useState<unknown>();
  const [saving, setSaving] = useState(false);
  const query = useQuery({
    queryKey: ["candidate", id],
    queryFn: ({ signal }) => get("/candidates/" + id, profileSchema, signal),
  });
  const proposals = useQuery({
    queryKey: ["proposals", id],
    queryFn: ({ signal }) =>
      get("/candidates/" + id + "/proposals", proposalsSchema, signal),
  });
  const history = useQuery({
    queryKey: ["history", id, historyBefore],
    queryFn: ({ signal }) =>
      get(
        "/candidates/" +
          id +
          "/history" +
          (historyBefore ? "?beforeId=" + historyBefore : ""),
        historySchema,
        signal,
      ),
  });
  async function act(
    path: string,
    method: string,
    data: Record<string, unknown>,
  ) {
    if (!query.data) return;
    setError(undefined);
    setSaving(true);
    try {
      const result = await change(
        "/candidates/" + id + path,
        method,
        { ...data, version: query.data.version },
        profileSchema,
      );
      client.setQueryData(["candidate", id], result);
      await Promise.all([
        client.invalidateQueries({ queryKey: ["proposals", id] }),
        client.invalidateQueries({ queryKey: ["history", id] }),
        client.invalidateQueries({ queryKey: ["candidates"] }),
      ]);
      setBasicEdit(false);
      setEditing(undefined);
      setProposalEditing(undefined);
    } catch (error) {
      setError(error);
      throw error;
    } finally {
      setSaving(false);
    }
  }
  const run = (path: string, method: string, data: Record<string, unknown>) => {
    void act(path, method, data).catch(() => {});
  };
  const selectEvidence = (doc: string, evidence: Evidence) => {
    setDocument(doc);
    setPage(evidence.page);
    setQuote(evidence.text);
  };
  if (query.isPending) return <p role="status">Đang tải dữ liệu…</p>;
  if (query.error)
    return (
      <p role="alert">
        {errorMessage(query.error)}{" "}
        <button onClick={() => void query.refetch()}>Thử lại</button>
      </p>
    );
  const p = query.data;
  const selected = p.documents.find((d) => d.id === document) ?? p.documents[0];
  return (
    <div className="candidate-screen">
      <h1>{p.basic.fullName || "Ứng viên #" + id}</h1>
      <p>
        Phiên bản {p.version} · Xác minh theo từng phần; thông tin chưa xác minh
        vẫn xem được.
      </p>
      {error !== undefined && (
        <div role="alert">
          {error instanceof RequestError && error.code === "VERSION_CONFLICT"
            ? "Xung đột phiên bản. Dữ liệu trên máy chủ đã thay đổi. Tải lại trước khi chỉnh tiếp."
            : errorMessage(error)}{" "}
          <button
            onClick={() => {
              setEditing(undefined);
              setBasicEdit(false);
              setProposalEditing(undefined);
              setError(undefined);
              void query.refetch();
              void proposals.refetch();
              void history.refetch();
            }}
          >
            Tải lại phiên bản mới
          </button>
        </div>
      )}
      <div className="candidate-split">
        <div>
          <section className="panel">
            <h2>Thông tin cơ bản</h2>
            <p>
              {stateLabel(p.basicState)} · Nguồn {p.basicOrigin}
            </p>
            <p>
              Họ tên: {p.basic.fullName || "Chưa có"} · Email:{" "}
              {p.basic.email || "Chưa có"} · Điện thoại:{" "}
              {p.basic.phone || "Chưa có"}
            </p>
            <p>
              {p.basic.location} {p.basic.summary}
            </p>
            <button onClick={() => setBasicEdit(true)}>
              Sửa thông tin cơ bản
            </button>{" "}
            <button
              disabled={saving}
              onClick={() => run("/basic", "PUT", { confirm: true })}
            >
              Xác nhận thông tin cơ bản
            </button>
            {basicEdit && (
              <BasicForm
                key={p.version}
                profile={p}
                onSave={(data) =>
                  act("/basic", "PUT", { data, confirm: false })
                }
                onCancel={() => setBasicEdit(false)}
              />
            )}
          </section>
          <section>
            <h2>Kinh nghiệm</h2>
            <button onClick={() => setEditing("new")}>Thêm kinh nghiệm</button>
            {p.experiences.length === 0 && (
              <p>Chưa có kinh nghiệm hiện hành.</p>
            )}
            {editing === "new" && (
              <ExperienceForm
                onSave={(data) =>
                  act("/experiences", "POST", { data, operation: "SAVE" })
                }
                onCancel={() => setEditing(undefined)}
              />
            )}
            {p.experiences.map((exp) => (
              <article className="panel" key={exp.id}>
                <p className="badge">
                  {stateLabel(exp.state)} · Nguồn {exp.origin}
                </p>
                <Values
                  data={exp.data}
                  documentId={exp.documentId}
                  onEvidence={selectEvidence}
                />
                <button onClick={() => setEditing(exp.id)}>
                  Sửa kinh nghiệm
                </button>{" "}
                <button
                  disabled={saving}
                  onClick={() =>
                    run("/experiences", "POST", {
                      experienceId: exp.id,
                      operation: "CONFIRM",
                    })
                  }
                >
                  Xác nhận kinh nghiệm
                </button>{" "}
                <button
                  disabled={saving}
                  onClick={() => {
                    if (
                      window.confirm(
                        "Xóa kinh nghiệm? Lịch sử và dấu xóa vẫn được giữ.",
                      )
                    )
                      run("/experiences", "POST", {
                        experienceId: exp.id,
                        operation: "DELETE",
                      });
                  }}
                >
                  Xóa kinh nghiệm
                </button>
                {editing === exp.id && (
                  <ExperienceForm
                    initial={exp.data}
                    onSave={(data) =>
                      act("/experiences", "POST", {
                        experienceId: exp.id,
                        data,
                        operation: "SAVE",
                      })
                    }
                    onCancel={() => setEditing(undefined)}
                  />
                )}
              </article>
            ))}
          </section>
          <section className="panel">
            <h2>Vấn đề và đề xuất cần xem</h2>
            <details>
              <summary>Vấn đề nguồn và lần mapping gần nhất</summary>
              <pre className="raw-text">
                {JSON.stringify(p.extensions, null, 2)}
              </pre>
            </details>
            {proposals.isPending ? (
              <p role="status">Đang tải đề xuất…</p>
            ) : proposals.error ? (
              <p role="alert">
                {errorMessage(proposals.error)}{" "}
                <button onClick={() => void proposals.refetch()}>
                  Thử lại đề xuất
                </button>
              </p>
            ) : proposals.data.length === 0 ? (
              <p>Không có đề xuất đang mở.</p>
            ) : (
              proposals.data.map((proposal) => (
                <article key={proposal.id} className="panel">
                  <h3>Đề xuất từ tài liệu #{proposal.documentId}</h3>
                  <Values
                    data={proposal.data}
                    documentId={proposal.documentId}
                    onEvidence={selectEvidence}
                  />
                  <label>
                    Đối sánh do người dùng chọn
                    <select
                      value={targets[proposal.id] ?? ""}
                      onChange={(e) =>
                        setTargets({
                          ...targets,
                          [proposal.id]: e.target.value,
                        })
                      }
                    >
                      <option value="">Chọn thêm mới hoặc thay thế</option>
                      <option value="ADD">Thêm kinh nghiệm mới</option>
                      {p.experiences.map((exp) => (
                        <option key={exp.id} value={exp.id}>
                          Thay: {exp.data.company.raw} — {exp.data.title.raw}
                        </option>
                      ))}
                    </select>
                  </label>
                  {targets[proposal.id] && targets[proposal.id] !== "ADD" && (
                    <>
                      <h4>Giá trị hiện hành</h4>
                      <pre className="raw-text">
                        {JSON.stringify(
                          p.experiences.find(
                            (exp) => exp.id === targets[proposal.id],
                          )?.data,
                          null,
                          2,
                        )}
                      </pre>
                    </>
                  )}
                  <button
                    disabled={saving || !targets[proposal.id]}
                    onClick={() =>
                      run("/proposals/" + proposal.id, "POST", {
                        decision: "ACCEPT",
                        targetExperienceId:
                          targets[proposal.id] === "ADD"
                            ? null
                            : targets[proposal.id],
                      })
                    }
                  >
                    Chấp nhận đề xuất
                  </button>{" "}
                  <button
                    disabled={!targets[proposal.id]}
                    onClick={() => setProposalEditing(proposal.id)}
                  >
                    Sửa đề xuất rồi lưu
                  </button>{" "}
                  <button
                    disabled={saving}
                    onClick={() =>
                      run("/proposals/" + proposal.id, "POST", {
                        decision: "REJECT",
                      })
                    }
                  >
                    Từ chối đề xuất
                  </button>
                  {proposalEditing === proposal.id && (
                    <ExperienceForm
                      initial={proposal.data}
                      onSave={(data) =>
                        act("/proposals/" + proposal.id, "POST", {
                          decision: "EDIT",
                          targetExperienceId:
                            targets[proposal.id] === "ADD"
                              ? null
                              : targets[proposal.id],
                          data,
                        })
                      }
                      onCancel={() => setProposalEditing(undefined)}
                    />
                  )}
                </article>
              ))
            )}
            <p className="footnote">
              Danh sách đề xuất có giới hạn mỗi lượt; sau xử lý, các đề xuất kế
              tiếp sẽ xuất hiện.
            </p>
          </section>
          <section className="panel">
            <h2>Lịch sử thay đổi</h2>
            {history.isPending ? (
              <p role="status">Đang tải lịch sử…</p>
            ) : history.error ? (
              <p role="alert">
                {errorMessage(history.error)}{" "}
                <button onClick={() => void history.refetch()}>
                  Thử lại lịch sử
                </button>
              </p>
            ) : (
              <>
                {history.data.length === 0 ? (
                  <p>Chưa có lịch sử.</p>
                ) : (
                  history.data.map((item) => (
                    <details key={item.id}>
                      <summary>
                        v{item.version} · {item.actor} · {item.action} ·{" "}
                        {new Date(item.at).toLocaleString("vi-VN")}
                      </summary>
                      <h3>Trước</h3>
                      <pre className="raw-text">
                        {JSON.stringify(item.before, null, 2)}
                      </pre>
                      <h3>Sau</h3>
                      <pre className="raw-text">
                        {JSON.stringify(item.after, null, 2)}
                      </pre>
                    </details>
                  ))
                )}
                <button
                  disabled={!historyBefore}
                  onClick={() => setHistoryBefore(undefined)}
                >
                  Lịch sử mới nhất
                </button>{" "}
                <button
                  disabled={!history.data.length}
                  onClick={() => setHistoryBefore(history.data.at(-1)?.id)}
                >
                  Lịch sử cũ hơn
                </button>
              </>
            )}
          </section>
        </div>
        <aside className="source-column">
          <form
            onSubmit={(event) => {
              event.preventDefault();
              if (validRouteId(linkDocumentId))
                run("/documents", "POST", {
                  documentId: Number(linkDocumentId),
                });
            }}
          >
            <label>
              ID tài liệu đã nhập cần liên kết
              <input
                inputMode="numeric"
                value={linkDocumentId}
                onChange={(event) => setLinkDocumentId(event.target.value)}
                required
              />
            </label>
            <button
              type="submit"
              disabled={saving || !validRouteId(linkDocumentId)}
            >
              Liên kết tài liệu với hồ sơ này
            </button>
            <p>
              Chỉ gắn tài liệu chưa thuộc ứng viên khác; không tự gộp hai hồ sơ.
            </p>
          </form>
          <label htmlFor="source-document">Tài liệu nguồn</label>
          <select
            id="source-document"
            value={selected?.id ?? ""}
            onChange={(event) => {
              setDocument(event.target.value);
              setPage(1);
              setQuote("");
            }}
          >
            {p.documents.map((doc) => (
              <option key={doc.id} value={doc.id}>
                {doc.filename} · nhập hệ thống{" "}
                {new Date(doc.importedAt).toLocaleDateString("vi-VN")}
              </option>
            ))}
          </select>
          <p>Ngày nhận CV và ngày nội dung cập nhật: chưa có nguồn xác định.</p>
          {quote && (
            <blockquote aria-label="Quote evidence">{quote}</blockquote>
          )}
          {selected ? (
            <PdfPane
              key={selected.id}
              documentId={selected.id}
              page={page}
              onPage={(next) => {
                setPage(next);
                setQuote("");
              }}
            />
          ) : (
            <p>Chưa có tài liệu nguồn được liên kết.</p>
          )}
        </aside>
      </div>
    </div>
  );
}
