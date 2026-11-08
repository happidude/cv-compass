import { Button, Empty, ErrorNotice, Loading } from "../../shared/ui";
import { ExperienceForm } from "./experience-form";
import { ExperienceValues, type Evidence } from "./experience";
import { PersonalProposal } from "./personal-info";
import type { Basic, Profile, Proposal } from "./schema";
import type { UseQueryResult } from "@tanstack/react-query";
type Act = (
  path: string,
  method: string,
  data: Record<string, unknown>,
) => Promise<void>;
/** Open proposals: every decision is explicit and versioned. */
export function ProposalsSection({
  profile,
  proposals,
  saving,
  targets,
  editing,
  onTarget,
  onEdit,
  onEvidence,
  act,
  run,
}: {
  profile: Profile;
  proposals: UseQueryResult<Proposal[]>;
  saving: boolean;
  targets: Record<string, string>;
  editing?: string;
  onTarget: (proposalId: string, target: string) => void;
  onEdit: (proposalId?: string) => void;
  onEvidence: (doc: string, evidence: Evidence) => void;
  act: Act;
  run: (path: string, method: string, data: Record<string, unknown>) => void;
}) {
  const p = profile;
  return (
    <section
      className="profile-section"
      id="proposals"
      aria-labelledby="proposals-title"
    >
      <header className="profile-section__header">
        <div>
          <h2 id="proposals-title">Vấn đề và đề xuất cần xem</h2>
          <p className="panel__subtitle">
            Danh sách đề xuất có giới hạn mỗi lượt; sau xử lý, các đề xuất kế
            tiếp sẽ xuất hiện.
          </p>
        </div>
      </header>
      <div className="profile-section__body">
        {proposals.isPending ? (
          <Loading label="Đang tải đề xuất…" rows={3} />
        ) : proposals.error ? (
          <ErrorNotice
            error={proposals.error}
            retry={() => void proposals.refetch()}
          />
        ) : proposals.data.length === 0 ? (
          <Empty inline>Không có đề xuất đang mở.</Empty>
        ) : (
          proposals.data.map((proposal) =>
            proposal.kind === "PERSONAL_INFO" ? (
              <PersonalProposal
                key={proposal.id}
                proposal={proposal}
                basic={p.basic as Basic}
                documents={p.documents}
                saving={saving}
                onEvidence={onEvidence}
                onDecision={(decision, edited) =>
                  act("/proposals/" + proposal.id, "POST", {
                    decision,
                    targetExperienceId: null,
                    ...(edited ? { data: edited } : {}),
                  })
                }
              />
            ) : (
              <section key={proposal.id} className="proposal">
                <h3>Đề xuất từ tài liệu #{proposal.documentId}</h3>
                <ExperienceValues
                  data={proposal.data}
                  documentId={proposal.documentId}
                  onEvidence={onEvidence}
                />
                <label className="field proposal__target">
                  Đối sánh do người dùng chọn
                  <select
                    className="select"
                    value={targets[proposal.id] ?? ""}
                    onChange={(e) => onTarget(proposal.id, e.target.value)}
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
                  <div className="compare__side">
                    <h4>Giá trị hiện hành</h4>
                    <pre className="text-block text-block--scroll">
                      {JSON.stringify(
                        p.experiences.find(
                          (exp) => exp.id === targets[proposal.id],
                        )?.data,
                        null,
                        2,
                      )}
                    </pre>
                  </div>
                )}
                <div className="btn-row">
                  <Button
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
                  </Button>
                  <Button
                    variant="secondary"
                    disabled={!targets[proposal.id]}
                    onClick={() => onEdit(proposal.id)}
                  >
                    Sửa đề xuất rồi lưu
                  </Button>
                  <Button
                    variant="danger"
                    disabled={saving}
                    onClick={() =>
                      run("/proposals/" + proposal.id, "POST", {
                        decision: "REJECT",
                      })
                    }
                  >
                    Từ chối đề xuất
                  </Button>
                </div>
                {editing === proposal.id && (
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
                    onCancel={() => onEdit(undefined)}
                  />
                )}
              </section>
            ),
          )
        )}
        <details className="disclosure">
          <summary>Vấn đề nguồn và lần mapping gần nhất</summary>
          <pre className="text-block text-block--scroll">
            {JSON.stringify(p.extensions, null, 2)}
          </pre>
        </details>
      </div>
    </section>
  );
}
