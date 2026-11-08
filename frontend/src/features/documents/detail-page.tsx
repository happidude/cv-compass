import { Link, useParams } from "react-router-dom";
import { Download, ExternalLink, RefreshCw } from "lucide-react";
import { useDocument } from "./queries";
import { documentRoute } from "./route";
import { OcrResult, Issue } from "./ocr-result";
import { validRouteId } from "../../shared/schema";
import { activeJob, usePollingBudget } from "../../shared/polling";
import { date, bytes } from "../../shared/format";
import { apiUrl } from "../../shared/session";
import {
  Button,
  Empty,
  ErrorNotice,
  Filename,
  Loading,
  Notice,
  PageHeader,
  Panel,
  RouteLine,
  Status,
  statusInfo,
  type Tone,
} from "../../shared/ui";
export function DocumentPage() {
  const { id = "" } = useParams();
  return validRouteId(id) ? (
    <DocumentContent key={id} id={id} />
  ) : (
    <Empty>ID CV không hợp lệ.</Empty>
  );
}
const jobTone = (status: string): Tone =>
  status === "FAILED" ? "bad" : status === "PARTIAL" ? "warn" : "neutral";
function DocumentContent({ id }: { id: string }) {
  const budget = usePollingBudget();
  const q = useDocument(id, budget.started);
  const d = q.data;
  if (!d)
    return q.isError ? (
      <ErrorNotice error={q.error} retry={() => void q.refetch()} />
    ) : (
      <Loading rows={6} />
    );
  return (
    <>
      <PageHeader
        title={<Filename name={d.originalFilename} />}
        meta={
          <>
            <span>Upload {date(d.createdAt)}</span>
            <span>{bytes(d.sizeBytes)}</span>
          </>
        }
        actions={
          <>
            <a
              className="btn btn--primary"
              href={apiUrl(`/documents/${id}/content`)}
              target="_blank"
              rel="noreferrer"
            >
              <ExternalLink size={16} aria-hidden="true" />
              Xem PDF gốc
            </a>
            <a
              className="btn btn--secondary"
              href={apiUrl(`/documents/${id}/content?download=true`)}
            >
              <Download size={16} aria-hidden="true" />
              Tải PDF gốc
            </a>
          </>
        }
      />
      <section className="panel route-panel" aria-labelledby="route-title">
        <h2 id="route-title" className="sr-only">
          Tuyến xử lý
        </h2>
        <RouteLine
          labelledBy="route-title"
          stops={documentRoute(d.processing.status)}
        />
        <Notice tone={jobTone(d.processing.status)}>
          <p>{statusInfo("job", d.processing.status)[2]}</p>
        </Notice>
      </section>
      {q.isError && <ErrorNotice error={q.error} />}
      <div className="detail-grid">
        <div className="stack">
          <Panel title="Metadata" padded>
            <dl className="dl-grid">
              <dt>Tên file</dt>
              <dd>
                <Filename name={d.originalFilename} />
              </dd>
              <dt>Định dạng</dt>
              <dd>{d.mimeType}</dd>
              <dt>Dung lượng</dt>
              <dd>{bytes(d.sizeBytes)}</dd>
              <dt>Số trang</dt>
              <dd>{d.ocr.pageCount ?? "—"}</dd>
              {d.batchId && (
                <>
                  <dt>Batch</dt>
                  <dd>
                    <Link to={`/batches/${d.batchId}`}>Batch #{d.batchId}</Link>
                  </dd>
                </>
              )}
            </dl>
          </Panel>
          <Panel
            title="Job OCR"
            padded
            actions={
              <Button
                variant="secondary"
                size="sm"
                disabled={q.isFetching}
                onClick={() => {
                  budget.restart();
                  void q.refetch();
                }}
              >
                <RefreshCw size={14} aria-hidden="true" />
                Cập nhật thủ công
              </Button>
            }
          >
            <dl className="dl-grid">
              <dt>Trạng thái</dt>
              <dd>
                <Status domain="job" value={d.processing.status} />
              </dd>
              <dt>Bắt đầu</dt>
              <dd>{date(d.processing.startedAt)}</dd>
              <dt>Kết thúc</dt>
              <dd>{date(d.processing.completedAt)}</dd>
              <dt>Cập nhật lúc</dt>
              <dd>{date(new Date(q.dataUpdatedAt).toISOString())}</dd>
            </dl>
            {d.processing.errorCode && <Issue code={d.processing.errorCode} />}
            {budget.expired && activeJob(d.processing.status) && (
              <Notice tone="neutral">
                <p>Đã dừng tự cập nhật sau thời gian giới hạn.</p>
              </Notice>
            )}
            <p className="form-note">
              “Cập nhật lúc” là thời gian trình duyệt nhận dữ liệu.
            </p>
          </Panel>
        </div>
        <Panel title="Nội dung trích xuất theo trang" padded>
          <OcrResult ocr={d.ocr} processing={d.processing} />
        </Panel>
      </div>
    </>
  );
}
