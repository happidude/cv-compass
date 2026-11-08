import { useState } from "react";
import { Link } from "react-router-dom";
import { ArrowRight, Upload } from "lucide-react";
import {
  dedupeDocuments,
  documentRoute,
  inProgress,
  needsAttention,
  useDocuments,
} from "../documents";
import { useBatches } from "../batches";
import { stateLabel, stateTone, useCandidates } from "../candidates";
import { date, displayName } from "../../shared/format";
import {
  Badge,
  Button,
  CountBar,
  Empty,
  ErrorNotice,
  Filename,
  Loading,
  PageHeader,
  Panel,
  RouteLine,
  Segmented,
  Status,
} from "../../shared/ui";

type Filter = "attention" | "running" | "all";
const SIDE_ROWS = 5;

/**
 * Hub screen. Everything here is read from list APIs that already exist, and
 * every count is scoped to the rows actually loaded (no invented totals).
 */
export function OverviewPage() {
  return (
    <>
      <PageHeader
        title="Tổng quan"
        description="Việc cần xử lý trong kho CV, tính trên các bản ghi mới nhất."
        actions={
          <>
            <Button variant="secondary" asChild>
              <Link to="/candidates">Mở hồ sơ ứng viên</Link>
            </Button>
            <Button asChild>
              <Link to="/documents/upload">
                <Upload size={16} aria-hidden="true" />
                Upload CV
              </Link>
            </Button>
          </>
        }
      />
      <div className="overview-grid">
        <WorkQueue />
        <div className="stack">
          <RecentCandidates />
          <RecentBatches />
        </div>
      </div>
    </>
  );
}

function WorkQueue() {
  const q = useDocuments();
  const [filter, setFilter] = useState<Filter>("attention");
  const rows = dedupeDocuments(q.data?.pages.slice(0, 1) ?? []);
  const attention = rows.filter((r) => needsAttention(r.processing.status));
  const running = rows.filter((r) => inProgress(r.processing.status));
  const shown =
    filter === "attention" ? attention : filter === "running" ? running : rows;
  return (
    <Panel
      title="CV cần theo dõi"
      subtitle={
        q.data
          ? `Trong ${rows.length} CV mới nhất`
          : "Tuyến xử lý của từng CV: Lưu file → OCR → Hồ sơ"
      }
      actions={
        q.data && (
          <Segmented
            label="Lọc CV"
            value={filter}
            onChange={setFilter}
            options={[
              {
                value: "attention",
                label: "Cần chú ý",
                count: attention.length,
              },
              { value: "running", label: "Đang xử lý", count: running.length },
              { value: "all", label: "Tất cả", count: rows.length },
            ]}
          />
        )
      }
      footer={
        <>
          <span>Trạng thái hồ sơ chưa có trên API nên trạm cuối để trống.</span>
          <Link to="/documents" className="link-arrow">
            Tất cả CV <ArrowRight size={14} aria-hidden="true" />
          </Link>
        </>
      }
    >
      {q.isPending ? (
        <Loading rows={6} />
      ) : q.isError && !rows.length ? (
        <ErrorNotice error={q.error} retry={() => void q.refetch()} />
      ) : !rows.length ? (
        <Empty>
          Chưa có CV nào. <Link to="/documents/upload">Upload CV đầu tiên</Link>{" "}
          để bắt đầu tuyến xử lý.
        </Empty>
      ) : !shown.length ? (
        <Empty>
          {filter === "attention"
            ? "Không có CV OCR lỗi hoặc chỉ hoàn tất một phần trong nhóm này."
            : "Không có CV đang chờ hoặc đang xử lý trong nhóm này."}
        </Empty>
      ) : (
        <ul className="queue">
          {shown.map((row) => (
            <li key={row.metadata.id} className="queue__item">
              <div className="queue__file">
                <Link
                  className="row-link"
                  to={`/documents/${row.metadata.id}`}
                  aria-label={`Mở CV ${displayName(row.metadata.originalFilename)}`}
                >
                  <Filename name={row.metadata.originalFilename} />
                </Link>
                <span className="queue__date">
                  {date(row.metadata.createdAt)}
                </span>
              </div>
              <RouteLine
                compact
                label="Tuyến xử lý"
                stops={documentRoute(row.processing.status)}
              />
              <Status domain="job" value={row.processing.status} />
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

function RecentCandidates() {
  const q = useCandidates();
  return (
    <Panel
      title="Hồ sơ mới nhập"
      footer={
        <Link to="/candidates" className="link-arrow">
          Tất cả hồ sơ <ArrowRight size={14} aria-hidden="true" />
        </Link>
      }
    >
      {q.isPending ? (
        <Loading rows={3} />
      ) : q.error ? (
        <ErrorNotice error={q.error} retry={() => void q.refetch()} />
      ) : !q.data.content.length ? (
        <Empty>Chưa có hồ sơ ứng viên.</Empty>
      ) : (
        <ul className="mini-list">
          {q.data.content.slice(0, SIDE_ROWS).map((row) => (
            <li key={row.id}>
              <Link className="row-link" to={`/candidates/${row.id}`}>
                {row.fullName || "Ứng viên #" + row.id}
              </Link>
              <Badge tone={stateTone(row.basicState)} mark>
                {stateLabel(row.basicState)}
              </Badge>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

function RecentBatches() {
  const q = useBatches(0);
  return (
    <Panel
      title="Batch gần đây"
      footer={
        <Link to="/batches" className="link-arrow">
          Lịch sử đầy đủ <ArrowRight size={14} aria-hidden="true" />
        </Link>
      }
    >
      {q.isPending ? (
        <Loading rows={3} />
      ) : q.error ? (
        <ErrorNotice error={q.error} retry={() => void q.refetch()} />
      ) : !q.data.content.length ? (
        <Empty>Chưa có batch nào.</Empty>
      ) : (
        <ul className="mini-list">
          {q.data.content.slice(0, SIDE_ROWS).map((b) => (
            <li key={b.id} className="mini-list__batch">
              <Link
                className="row-link nums"
                to={`/batches/${b.id}`}
                aria-label={`Mở batch ${b.id}`}
              >
                #{b.id}
              </Link>
              <span className="mini-list__meta nums">
                {b.successCount}/{b.totalFiles} file
              </span>
              <CountBar
                total={b.totalFiles}
                good={b.successCount}
                bad={b.failedCount}
              />
              <Status domain="batch" value={b.status} />
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}
