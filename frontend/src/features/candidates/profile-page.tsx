import { useState } from "react";
import { useParams } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { Check, Pencil, Plus, Trash2 } from "lucide-react";
import { RequestError, errorMessage } from "../../shared/http";
import { change } from "../../shared/session";
import { validRouteId } from "../../shared/schema";
import {
  Badge,
  Button,
  Empty,
  ErrorNotice,
  Loading,
  PageHeader,
  RouteLine,
} from "../../shared/ui";
import { ExperienceForm } from "./experience-form";
import { ExperienceValues, type Evidence } from "./experience";
import { HistorySection } from "./history";
import { stateLabel, stateTone } from "./labels";
import { PersonalFields, PersonalForm } from "./personal-info";
import { ProposalsSection } from "./proposals";
import { useHistory, useProfile, useProposals } from "./queries";
import { profileRoute } from "./route";
import { profileSchema } from "./schema";
import { SourcePane } from "./source-pane";

export function CandidatePage() {
  const { id = "" } = useParams();
  return validRouteId(id) ? (
    <CandidateContent key={id} id={id} />
  ) : (
    <Empty>ID ứng viên không hợp lệ.</Empty>
  );
}

/** Review state chip; the exact text "<state> · Nguồn <origin>" is stable. */
function ReviewState({ state, origin }: { state: string; origin: string }) {
  return (
    <Badge tone={stateTone(state)} mark>
      {stateLabel(state)} · Nguồn {origin}
    </Badge>
  );
}

function CandidateContent({ id }: { id: string }) {
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
  const query = useProfile(id);
  const proposals = useProposals(id);
  const history = useHistory(id, historyBefore);
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
    if (!query.data?.documents.some((source) => source.id === doc)) return;
    setDocument(doc);
    setPage(evidence.page);
    setQuote(evidence.text);
  };
  if (query.isPending) return <Loading rows={8} />;
  if (query.error)
    return (
      <ErrorNotice error={query.error} retry={() => void query.refetch()} />
    );
  const p = query.data;
  const selected = p.documents.find((d) => d.id === document) ?? p.documents[0];
  return (
    <div className="profile">
      <PageHeader
        title={p.basic.fullName || "Ứng viên #" + id}
        meta={
          <>
            <span className="nums">Phiên bản {p.version}</span>
            <span>
              Xác minh theo từng phần; thông tin chưa xác minh vẫn xem được.
            </span>
          </>
        }
      />
      <section className="panel route-panel" aria-labelledby="review-route">
        <h2 id="review-route" className="sr-only">
          Tiến độ review
        </h2>
        <RouteLine
          label="Tiến độ review"
          stops={profileRoute(p, proposals.data)}
        />
      </section>
      {error !== undefined && (
        <div role="alert" className="notice notice--bad conflict">
          <p>
            {error instanceof RequestError && error.code === "VERSION_CONFLICT"
              ? "Xung đột phiên bản. Dữ liệu trên máy chủ đã thay đổi. Tải lại trước khi chỉnh tiếp."
              : errorMessage(error)}
          </p>
          <Button
            variant="secondary"
            size="sm"
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
          </Button>
        </div>
      )}
      <div className="candidate-split">
        <div className="profile__main">
          <nav className="section-nav" aria-label="Các phần của hồ sơ">
            <a href="#basic">Thông tin cơ bản</a>
            <a href="#experience">
              Kinh nghiệm <span className="nums">{p.experiences.length}</span>
            </a>
            <a href="#proposals">
              Đề xuất{" "}
              {proposals.data && (
                <span className="nums">{proposals.data.length}</span>
              )}
            </a>
            <a href="#history">Lịch sử</a>
          </nav>
          <section
            className="profile-section"
            id="basic"
            aria-labelledby="basic-title"
          >
            <header className="profile-section__header">
              <div>
                <h2 id="basic-title">Thông tin cơ bản</h2>
                <ReviewState state={p.basicState} origin={p.basicOrigin} />
              </div>
              <div className="btn-row">
                <Button variant="secondary" onClick={() => setBasicEdit(true)}>
                  <Pencil size={14} aria-hidden="true" />
                  Sửa thông tin cơ bản
                </Button>
                <Button
                  disabled={saving}
                  onClick={() => run("/basic", "PUT", { confirm: true })}
                >
                  <Check size={14} aria-hidden="true" />
                  Xác nhận thông tin cơ bản
                </Button>
              </div>
            </header>
            <div className="profile-section__body">
              {basicEdit && (
                <PersonalForm
                  key={p.version}
                  initial={p.basic}
                  onSave={(data) =>
                    act("/basic", "PUT", { data, confirm: false })
                  }
                  onCancel={() => setBasicEdit(false)}
                />
              )}
              <PersonalFields
                basic={p.basic}
                metadata={p.extensions.personalInfo}
                documents={p.documents}
                onEvidence={selectEvidence}
              />
            </div>
          </section>
          <section
            className="profile-section"
            id="experience"
            aria-labelledby="experience-title"
          >
            <header className="profile-section__header">
              <h2 id="experience-title">Kinh nghiệm</h2>
              <Button variant="secondary" onClick={() => setEditing("new")}>
                <Plus size={14} aria-hidden="true" />
                Thêm kinh nghiệm
              </Button>
            </header>
            <div className="profile-section__body">
              {editing === "new" && (
                <ExperienceForm
                  onSave={(data) =>
                    act("/experiences", "POST", { data, operation: "SAVE" })
                  }
                  onCancel={() => setEditing(undefined)}
                />
              )}
              {p.experiences.length === 0 && (
                <Empty inline>Chưa có kinh nghiệm hiện hành.</Empty>
              )}
              {p.experiences.map((exp) => (
                <article className="xp-card" key={exp.id}>
                  <div className="xp-card__state">
                    <ReviewState state={exp.state} origin={exp.origin} />
                  </div>
                  <ExperienceValues
                    data={exp.data}
                    documentId={exp.documentId}
                    onEvidence={selectEvidence}
                  />
                  <div className="btn-row">
                    <Button
                      variant="secondary"
                      size="sm"
                      onClick={() => setEditing(exp.id)}
                    >
                      <Pencil size={14} aria-hidden="true" />
                      Sửa kinh nghiệm
                    </Button>
                    <Button
                      size="sm"
                      disabled={saving}
                      onClick={() =>
                        run("/experiences", "POST", {
                          experienceId: exp.id,
                          operation: "CONFIRM",
                        })
                      }
                    >
                      <Check size={14} aria-hidden="true" />
                      Xác nhận kinh nghiệm
                    </Button>
                    <Button
                      variant="danger"
                      size="sm"
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
                      <Trash2 size={14} aria-hidden="true" />
                      Xóa kinh nghiệm
                    </Button>
                  </div>
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
            </div>
          </section>
          <ProposalsSection
            profile={p}
            proposals={proposals}
            saving={saving}
            targets={targets}
            editing={proposalEditing}
            onTarget={(proposalId, target) =>
              setTargets({ ...targets, [proposalId]: target })
            }
            onEdit={setProposalEditing}
            onEvidence={selectEvidence}
            act={act}
            run={run}
          />
          <HistorySection
            history={history}
            before={historyBefore}
            onBefore={setHistoryBefore}
          />
        </div>
        <SourcePane
          documents={p.documents}
          selectedId={selected?.id}
          page={page}
          quote={quote}
          saving={saving}
          onSelect={(documentId) => {
            setDocument(documentId);
            setPage(1);
            setQuote("");
          }}
          onPage={(next) => {
            setPage(next);
            setQuote("");
          }}
          onLink={(documentId) => run("/documents", "POST", { documentId })}
        />
      </div>
    </div>
  );
}
