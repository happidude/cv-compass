import { Link, useParams } from "react-router-dom";
import { useDocuments, useDocument } from "./queries";
import { dedupeDocuments } from "./api";
import { validRouteId } from "../../shared/schema";
import { activeJob, usePollingBudget } from "../../shared/polling";
import { date, bytes, displayName } from "../../shared/format";
import {
  Heading,
  Filename,
  Loading,
  Empty,
  ErrorNotice,
  LongText,
} from "../../shared/ui/common";
import { Status, statusInfo } from "../../shared/ui/status";
import { Button } from "../../shared/ui/button";
import { errorLabel } from "../../shared/http";
import type { DocumentDetail } from "./schema";
export function DocumentsPage() {
  const q = useDocuments();
  const rows = dedupeDocuments(q.data?.pages ?? []);
  return (
    <>
      <Heading
        title="Quản lý CV"
        description="Kho CV đã lưu và trạng thái xử lý OCR."
        action={
          <Button asChild>
            <Link to="/documents/upload">Upload CV</Link>
          </Button>
        }
      />
      <section className="panel">
        {q.isPending ? (
          <Loading />
        ) : !rows.length && !q.isError ? (
          <Empty>Chưa có CV. Chọn Upload CV để bắt đầu.</Empty>
        ) : (
          rows.length > 0 && (
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Tên file</th>
                    <th>Ngày upload</th>
                    <th>Dung lượng</th>
                    <th>Trạng thái xử lý</th>
                    <th>
                      <span className="sr-only">Thao tác</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.metadata.id}>
                      <td>
                        <Filename name={row.metadata.originalFilename} />
                      </td>
                      <td>{date(row.metadata.createdAt)}</td>
                      <td>{bytes(row.metadata.sizeBytes)}</td>
                      <td>
                        <Status domain="job" value={row.processing.status} />
                      </td>
                      <td>
                        <Link
                          aria-label={`Xem ${displayName(row.metadata.originalFilename)}`}
                          to={`/documents/${row.metadata.id}`}
                        >
                          Xem
                        </Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )
        )}
        {q.isError && (
          <ErrorNotice
            error={q.error}
            retry={() => {
              if (q.isFetchNextPageError) void q.fetchNextPage();
              else void q.refetch();
            }}
          />
        )}
        {rows.length > 0 && (
          <div className="table-footer">
            <span>Đang hiển thị {rows.length} CV</span>
            {q.hasNextPage ? (
              <Button
                variant="outline"
                disabled={q.isFetching}
                onClick={() => void q.fetchNextPage()}
              >
                {q.isFetchingNextPage ? "Đang tải thêm…" : "Tải thêm"}
              </Button>
            ) : (
              <span>Đã hết dữ liệu</span>
            )}
          </div>
        )}
      </section>
      <p className="footnote">
        Lưu ý: upload thành công không đồng nghĩa OCR thành công. Danh sách dùng
        cursor nên không có tổng số trang.
      </p>
    </>
  );
}
function OcrResult({
  ocr,
  processing,
}: Pick<DocumentDetail, "ocr" | "processing">) {
  if (ocr.availability === "NONE")
    return (
      <Empty>
        Chưa có kết quả OCR. {statusInfo("job", processing.status)[2]}
      </Empty>
    );
  if (ocr.availability === "LEGACY")
    return (
      <>
        <p className="notice">Kết quả cũ — nội dung được lưu nguyên khối.</p>
        {ocr.legacyRawText === null ? (
          <Empty>Kết quả cũ không có văn bản</Empty>
        ) : (
          <LongText text={ocr.legacyRawText} />
        )}
      </>
    );
  if (ocr.availability !== "PAGE_RESULT")
    return <Empty>Không xác định loại kết quả OCR.</Empty>;
  return (
    <>
      {ocr.status && (
        <p>
          Kết quả: <Status domain="result" value={ocr.status} />
        </p>
      )}
      {ocr.warnings.map((code, i) => (
        <Issue key={i} code={code} warning />
      ))}
      {ocr.errors.map((e, i) => (
        <Issue key={i} {...e} />
      ))}
      {!ocr.pages.length && <Empty>Chưa có trang kết quả.</Empty>}
      {ocr.pages.map((page) => (
        <article className="ocr-page" key={page.pageNumber}>
          <h3>
            Trang {page.pageNumber} <Status domain="page" value={page.status} />
          </h3>
          <h4>Nội dung trích xuất</h4>
          {page.rawText ? (
            <LongText text={page.rawText} />
          ) : (
            <p className="muted">{statusInfo("page", page.status)[2]}</p>
          )}
          {page.warnings.map((code, i) => (
            <Issue key={i} code={code} warning />
          ))}
          {page.error && <Issue {...page.error} />}
        </article>
      ))}
    </>
  );
}
function Issue({
  code,
  stage,
  warning = false,
}: {
  code: string;
  stage?: string;
  warning?: boolean;
}) {
  const safe = /^[A-Za-z0-9_.:-]{1,100}$/;
  return (
    <p className={`notice ${warning ? "warn" : "bad"}`}>
      {errorLabel(code) ??
        (warning ? "Có cảnh báo khi xử lý OCR." : "Có lỗi khi xử lý OCR.")}{" "}
      {safe.test(code) && <span>Mã: {code}</span>}{" "}
      {stage && safe.test(stage) && <span> · Bước: {stage}</span>}
    </p>
  );
}
export function DocumentPage() {
  const { id = "" } = useParams();
  return validRouteId(id) ? (
    <DocumentContent key={id} id={id} />
  ) : (
    <Empty>ID CV không hợp lệ.</Empty>
  );
}
function DocumentContent({ id }: { id: string }) {
  const budget = usePollingBudget();
  const q = useDocument(id, budget.started);
  const d = q.data;
  if (!d)
    return q.isError ? (
      <ErrorNotice error={q.error} retry={() => void q.refetch()} />
    ) : (
      <Loading />
    );
  return (
    <>
      <Heading
        title={<Filename name={d.originalFilename} />}
        description={
          <>
            Upload {date(d.createdAt)} · {bytes(d.sizeBytes)}
          </>
        }
        action={
          <div>
            <Button variant="outline" disabled>
              Xem PDF (chưa hỗ trợ)
            </Button>
            <p className="footnote">
              Chưa có API xem/tải PDF dành cho người dùng.
            </p>
          </div>
        }
      />
      <p
        className={`notice ${d.processing.status === "FAILED" ? "bad" : d.processing.status === "PARTIAL" ? "warn" : ""}`}
      >
        {statusInfo("job", d.processing.status)[2]}
      </p>
      {q.isError && <ErrorNotice error={q.error} />}
      <div className="detail-grid">
        <div className="stack">
          <section className="panel padded">
            <h2>Metadata</h2>
            <dl>
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
          </section>
          <section className="panel padded">
            <h2>Job OCR</h2>
            <dl>
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
              <p>Đã dừng tự cập nhật sau thời gian giới hạn.</p>
            )}
            <Button
              variant="outline"
              disabled={q.isFetching}
              onClick={() => {
                budget.restart();
                void q.refetch();
              }}
            >
              Cập nhật thủ công
            </Button>
            <p className="footnote">
              “Cập nhật lúc” là thời gian trình duyệt nhận dữ liệu.
            </p>
          </section>
        </div>
        <section className="panel padded">
          <h2>Nội dung trích xuất theo trang</h2>
          <OcrResult ocr={d.ocr} processing={d.processing} />
        </section>
      </div>
    </>
  );
}
export { OcrResult };
