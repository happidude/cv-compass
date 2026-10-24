import { Link, useParams, useSearchParams } from "react-router-dom";
import { useEffect } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { batchKeys } from "./api";
import { useBatches, useBatch, useItems } from "./queries";
import { validRouteId } from "../../shared/schema";
import { usePollingBudget, activeBatch } from "../../shared/polling";
import { date } from "../../shared/format";
import {
  Heading,
  Filename,
  Loading,
  Empty,
  ErrorNotice,
  Pagination,
} from "../../shared/ui/common";
import { Button } from "../../shared/ui/button";
import { Status } from "../../shared/ui/status";
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
      <Heading
        title="Lịch sử nhập"
        description="Kết quả lưu file của các batch CV."
      />
      <section className="panel">
        {q.isPending && <Loading />}
        {q.isError && (
          <ErrorNotice error={q.error} retry={() => void q.refetch()} />
        )}
        {q.data && (
          <>
            {q.data.content.length ? (
              <div className="table-scroll">
                <table>
                  <thead>
                    <tr>
                      <th>Mã batch</th>
                      <th>Thời gian tạo</th>
                      <th>Tổng file</th>
                      <th>Upload thành công</th>
                      <th>Upload thất bại</th>
                      <th>Trạng thái nhập</th>
                      <th>
                        <span className="sr-only">Thao tác</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {q.data.content.map((b) => (
                      <tr key={b.id}>
                        <td>#{b.id}</td>
                        <td>{date(b.createdAt)}</td>
                        <td>{b.totalFiles}</td>
                        <td>{b.successCount}</td>
                        <td>{b.failedCount}</td>
                        <td>
                          <Status domain="batch" value={b.status} />
                        </td>
                        <td>
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
      </section>
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
      <Heading
        title={
          <>
            Batch #{id} <Status domain="batch" value={b.status} />
          </>
        }
        description={`Tạo lúc ${date(b.createdAt)}`}
      />
      {q.isError && <ErrorNotice error={q.error} />}
      <div className="stats">
        {[
          ["Tổng file", b.totalFiles],
          ["Upload thành công", b.successCount],
          ["Upload thất bại", b.failedCount],
          [
            "Chưa có kết quả upload",
            Math.max(0, b.totalFiles - b.successCount - b.failedCount),
          ],
        ].map(([label, value]) => (
          <section className="panel padded" key={label}>
            <p>{label}</p>
            <strong>{value}</strong>
          </section>
        ))}
      </div>
      <section className="panel">
        {items.isPending && <Loading />}
        {items.isError && (
          <ErrorNotice error={items.error} retry={() => void items.refetch()} />
        )}
        {items.data && (
          <>
            {items.data.content.length ? (
              <div className="table-scroll">
                <table>
                  <thead>
                    <tr>
                      <th>Tên file</th>
                      <th>Kết quả upload</th>
                      <th>Mã lỗi</th>
                      <th>
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
                            <span className="badge good">
                              ● Upload thành công
                            </span>
                          ) : item.errorCode ? (
                            <span className="badge bad">● Upload thất bại</span>
                          ) : (
                            <span className="badge neutral">Chưa xác định</span>
                          )}
                        </td>
                        <td>
                          {item.errorCode &&
                          /^[A-Z0-9_]{1,100}$/.test(item.errorCode)
                            ? item.errorCode
                            : "—"}
                        </td>
                        <td>
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
      </section>
      <div className="table-footer">
        <span>
          Cập nhật lúc {date(new Date(q.dataUpdatedAt).toISOString())} (trình
          duyệt nhận dữ liệu)
        </span>
        <Button
          variant="outline"
          disabled={q.isFetching || items.isFetching}
          onClick={() => {
            budget.restart();
            void q.refetch();
            void client.invalidateQueries({
              queryKey: batchKeys.items(id, page),
            });
          }}
        >
          Cập nhật thủ công
        </Button>
      </div>
      {budget.expired && activeBatch(b.status) && (
        <p>Đã dừng tự cập nhật sau thời gian giới hạn.</p>
      )}
    </>
  );
}
