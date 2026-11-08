import { Link } from "react-router-dom";
import { Upload } from "lucide-react";
import { useDocuments } from "./queries";
import { dedupeDocuments } from "./api";
import { documentRoute } from "./route";
import { date, bytes, displayName } from "../../shared/format";
import {
  Button,
  Empty,
  ErrorNotice,
  Filename,
  Loading,
  PageHeader,
  Panel,
  RouteLine,
  Status,
} from "../../shared/ui";
export function DocumentsPage() {
  const q = useDocuments();
  const rows = dedupeDocuments(q.data?.pages ?? []);
  return (
    <>
      <PageHeader
        title="Quản lý CV"
        description="Kho CV đã lưu và trạng thái xử lý OCR."
        actions={
          <Button asChild>
            <Link to="/documents/upload">
              <Upload size={16} aria-hidden="true" />
              Upload CV
            </Link>
          </Button>
        }
      />
      <Panel
        footer={
          rows.length > 0 && (
            <>
              <span className="nums">Đang hiển thị {rows.length} CV</span>
              {q.hasNextPage ? (
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={q.isFetching}
                  onClick={() => void q.fetchNextPage()}
                >
                  {q.isFetchingNextPage ? "Đang tải thêm…" : "Tải thêm"}
                </Button>
              ) : (
                <span>Đã hết dữ liệu</span>
              )}
            </>
          )
        }
      >
        {q.isPending ? (
          <Loading rows={6} />
        ) : !rows.length && !q.isError ? (
          <Empty>Chưa có CV. Chọn Upload CV để bắt đầu.</Empty>
        ) : (
          rows.length > 0 && (
            <div className="table-scroll">
              <table className="table">
                <thead>
                  <tr>
                    <th>Tên file</th>
                    <th>Ngày upload</th>
                    <th className="cell-num">Dung lượng</th>
                    <th>Trạng thái xử lý</th>
                    <th className="cell-action">
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
                      <td className="cell-nowrap cell-muted">
                        {date(row.metadata.createdAt)}
                      </td>
                      <td className="cell-num cell-muted">
                        {bytes(row.metadata.sizeBytes)}
                      </td>
                      <td>
                        <span className="route-cell">
                          <RouteLine
                            compact
                            label="Tuyến xử lý"
                            stops={documentRoute(row.processing.status)}
                          />
                          <Status domain="job" value={row.processing.status} />
                        </span>
                      </td>
                      <td className="cell-action">
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
      </Panel>
      <p className="footnote">
        Lưu ý: upload thành công không đồng nghĩa OCR thành công. Danh sách dùng
        cursor nên không có tổng số trang.
      </p>
    </>
  );
}
