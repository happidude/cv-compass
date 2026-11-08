import { useState } from "react";
import { Link } from "react-router-dom";
import { ChevronRight } from "lucide-react";
import { date } from "../../shared/format";
import {
  Badge,
  Button,
  Empty,
  ErrorNotice,
  Loading,
  PageHeader,
  Panel,
} from "../../shared/ui";
import { stateLabel, stateTone } from "./labels";
import { useCandidates } from "./queries";
export function CandidatesPage() {
  const [before, setBefore] = useState<string>();
  const query = useCandidates(before);
  return (
    <>
      <PageHeader
        title="Hồ sơ ứng viên"
        description="Hồ sơ xuất hiện ngay sau publication, có thể còn chưa xác minh. Không tự gộp theo tên/email/điện thoại."
      />
      <Panel
        footer={
          query.data && (
            <>
              <span>Sắp xếp: mới nhập trước</span>
              <div className="btn-row">
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={!before}
                  onClick={() => setBefore(undefined)}
                >
                  Về đầu
                </Button>
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={!query.data.hasNext}
                  onClick={() =>
                    setBefore(query.data.nextBeforeId ?? undefined)
                  }
                >
                  Trang tiếp
                  <ChevronRight size={14} aria-hidden="true" />
                </Button>
              </div>
            </>
          )
        }
      >
        {query.isPending ? (
          <Loading rows={6} />
        ) : query.error ? (
          <ErrorNotice error={query.error} retry={() => void query.refetch()} />
        ) : query.data.content.length === 0 ? (
          <Empty>Chưa có hồ sơ ứng viên.</Empty>
        ) : (
          <div className="table-scroll">
            <table className="table">
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
                      <Link className="row-link" to={"/candidates/" + row.id}>
                        {row.fullName || "Ứng viên #" + row.id}
                      </Link>
                    </td>
                    <td>
                      <Badge tone={stateTone(row.basicState)} mark>
                        {stateLabel(row.basicState)}
                      </Badge>
                    </td>
                    <td className="cell-nowrap cell-muted">
                      {date(row.importedAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>
    </>
  );
}
