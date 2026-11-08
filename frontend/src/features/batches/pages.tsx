import { Link, useParams, useSearchParams } from "react-router-dom";
import { useEffect } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { batchKeys } from "./api";
import { useBatches, useBatch, useItems } from "./queries";
import { validRouteId } from "../../shared/schema";
import { usePollingBudget, activeBatch } from "../../shared/polling";
import { date } from "../../shared/format";
import { RefreshCw } from "lucide-react";
import {
  Badge,
  Button,
  CountBar,
  Empty,
  ErrorNotice,
  Filename,
  Loading,
  Notice,
  PageHeader,
  Pagination,
  Panel,
  Status,
} from "../../shared/ui";
function usePage() {
  const [params, setParams] = useSearchParams();
  const raw = params.get("page") ?? "0";
  const page =
    /^\d+$/.test(raw) &&
    Number.isSafeInteger(Number(raw)) &&
    Number(raw) <= 2147483647
      ? Number(raw)
      : 0;
  return { page, change: (n: number) => setParams({ page: String(n) }) };
}
export function BatchesPage() {
  const { page, change } = usePage();
  const q = useBatches(page);
  return (
    <>
      <PageHeader
        title="Lịch sử nhập"
        description="Kết quả lưu file của các batch CV."
      />
      <Panel>
        {q.isPending && <Loading rows={6} />}
        {q.isError && (
          <ErrorNotice error={q.error} retry={() => void q.refetch()} />
        )}
        {q.data && (
          <>
            {q.data.content.length ? (
              <div className="table-scroll">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Mã batch</th>
                      <th>Thời gian tạo</th>
                      <th className="cell-num">Tổng file</th>
                      <th className="cell-num">Upload thành công</th>
                      <th className="cell-num">Upload thất bại</th>
                      <th>Kết quả</th>
                      <th>Trạng thái nhập</th>
                      <th className="cell-action">
                        <span className="sr-only">Thao tác</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {q.data.content.map((b) => (
                      <tr key={b.id}>
                        <td className="nums">#{b.id}</td>
                        <td className="cell-nowrap cell-muted">
                          {date(b.createdAt)}
                        </td>
                        <td className="cell-num">{b.totalFiles}</td>
                        <td className="cell-num">{b.successCount}</td>
                        <td className="cell-num">{b.failedCount}</td>
                        <td className="cell-bar">
                          <CountBar
                            total={b.totalFiles}
                            good={b.successCount}
                            bad={b.failedCount}
                          />
                        </td>
                        <td>
                          <Status domain="batch" value={b.status} />
                        </td>
                        <td className="cell-action">
                          <Link
                            aria-label={`Chi tiết batch ${b.id}`}
                            to={`/batches/${b.id}`}
                          >
                            Chi tiết
                          </Link>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <Empty>Chưa có batch ở trang này.</Empty>
            )}
            <Pagination
              page={page}
              totalPages={q.data.totalPages}
              busy={q.isFetching}
              change={change}
            />
          </>
        )}
      </Panel>
      <p className="footnote">
        Các số liệu mô tả upload, không phải kết quả OCR.
      </p>
    </>
  );
}
export function BatchPage() {
  const { id = "" } = useParams();
  return validRouteId(id) ? (
    <BatchContent key={id} id={id} />
  ) : (
    <Empty>ID batch không hợp lệ.</Empty>
  );
}
function BatchContent({ id }: { id: string }) {
  const { page, change } = usePage();
  const budget = usePollingBudget();
  const q = useBatch(id, budget.started);
  const items = useItems(id, page);
  const client = useQueryClient();
  useEffect(() => {
    if (q.dataUpdatedAt)
      void client.invalidateQueries({ queryKey: ["batches", "items", id] });
  }, [client, id, q.dataUpdatedAt]);
  if (!q.data)
    return q.isError ? (
      <ErrorNotice error={q.error} retry={() => void q.refetch()} />
    ) : (
      <Loading />
    );
  const b = q.data;
  return (
    <>
      <PageHeader
        title={
          <>
            Batch #{id} <Status domain="batch" value={b.status} />
          </>
        }
        description={`Tạo lúc ${date(b.createdAt)}`}
        actions={
          <Button
            variant="secondary"
            disabled={q.isFetching || items.isFetching}
            onClick={() => {
              budget.restart();
              void q.refetch();
              void client.invalidateQueries({
                queryKey: batchKeys.items(id, page),
              });
            }}
          >
            <RefreshCw size={16} aria-hidden="true" />
            Cập nhật thủ công
          </Button>
        }
      />
      {q.isError && <ErrorNotice error={q.error} />}
      <div className="stats">
        {(
          [
            ["Tổng file", b.totalFiles, ""],
            ["Upload thành công", b.successCount, "stat--good"],
            [
              "Upload thất bại",
              b.failedCount,
              b.failedCount ? "stat--bad" : "",
            ],
            [
              "Chưa có kết quả upload",
              Math.max(0, b.totalFiles - b.successCount - b.failedCount),
              "",
            ],
          ] as const
        ).map(([label, value, tone]) => (
          <section className={`stat ${tone}`} key={label}>
            <p>{label}</p>
            <strong>{value}</strong>
          </section>
        ))}
      </div>
      <Panel
        title="File trong batch"
        subtitle={`Cập nhật lúc ${date(new Date(q.dataUpdatedAt).toISOString())} (trình duyệt nhận dữ liệu)`}
      >
        {items.isPending && <Loading rows={4} />}
        {items.isError && (
          <ErrorNotice error={items.error} retry={() => void items.refetch()} />
        )}
        {items.data && (
          <>
            {items.data.content.length ? (
              <div className="table-scroll">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Tên file</th>
                      <th>Kết quả upload</th>
                      <th>Mã lỗi</th>
                      <th className="cell-action">
                        <span className="sr-only">Thao tác</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {items.data.content.map((item) => (
                      <tr key={item.fileOrder}>
                        <td>
                          <Filename name={item.displayName} />
                        </td>
                        <td>
                          {item.documentId ? (
                            <Badge tone="good" mark>
                              Upload thành công
                            </Badge>
                          ) : item.errorCode ? (
                            <Badge tone="bad" mark>
                              Upload thất bại
                            </Badge>
                          ) : (
                            <Badge tone="neutral">Chưa xác định</Badge>
                          )}
                        </td>
                        <td className="cell-muted nums">
                          {item.errorCode &&
                          /^[A-Z0-9_]{1,100}$/.test(item.errorCode)
                            ? item.errorCode
                            : "—"}
                        </td>
                        <td className="cell-action">
                          {item.documentId ? (
                            <Link to={`/documents/${item.documentId}`}>
                              Mở CV
                            </Link>
                          ) : (
                            "Chưa lưu"
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <Empty>Chưa có kết quả upload ở trang này.</Empty>
            )}
            <Pagination
              page={page}
              totalPages={items.data.totalPages}
              busy={items.isFetching}
              change={change}
            />
          </>
        )}
      </Panel>
      {budget.expired && activeBatch(b.status) && (
        <Notice tone="neutral" className="mt-4">
          <p>Đã dừng tự cập nhật sau thời gian giới hạn.</p>
        </Notice>
      )}
    </>
  );
}
