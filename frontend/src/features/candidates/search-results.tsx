import { Link } from "react-router-dom";
import {
  Badge,
  Button,
  Empty,
  ErrorNotice,
  Loading,
  Panel,
} from "../../shared/ui";
import { candidateTitle } from "./labels";
import { useCandidateSearch } from "./queries";
import type { SearchResult } from "./schema";

export const estimatedExperienceHint =
  "Tính từ các mốc thời gian trong CV, có thể chưa chính xác";
export function estimatedExperience(months: number | null) {
  return months === null
    ? "—"
    : months < 12
      ? `~${months} tháng`
      : `~${Math.floor(months / 12)} năm`;
}
export function matchReasonLabel(reason: string, companyName?: string | null) {
  switch (reason) {
    case "COMPANY_DIRECT":
      return companyName ? `Từng làm tại ${companyName}` : undefined;
    case "COMPANY_PARENT":
      return companyName
        ? `Từng làm tại ${companyName} (tập đoàn mẹ)`
        : undefined;
    case "COMPANY_CURRENT":
      return "Đang làm tại công ty khớp";
    case "COMPANY_TEXT":
      return "Tên công ty có trong hồ sơ";
    case "TITLE_CATALOG":
      return "Chức danh khớp";
    case "TITLE_CURRENT":
      return "Chức danh hiện tại khớp";
    case "TITLE_TEXT":
      return "Chức danh có trong hồ sơ";
    case "KEYWORD_TEXT":
      return "Khớp từ khóa";
    case "KEYWORD_FUZZY":
      return "Gần đúng từ khóa";
    default:
      return undefined;
  }
}
function SearchResultRow({ row }: { row: SearchResult }) {
  const reasons = row.matchReasons.flatMap((reason) => {
    const label = matchReasonLabel(reason, row.matchedCompany?.name);
    return label ? [label] : [];
  });
  return (
    <tr>
      <td>
        <Link className="row-link" to={"/candidates/" + row.id}>
          {candidateTitle(row.id, row.fullName)}
        </Link>
      </td>
      <td>
        {row.currentTitle ? (
          <>
            {row.currentTitle.name}{" "}
            {row.currentTitle.aiNormalized && (
              <Badge tone="info">AI đề xuất</Badge>
            )}
          </>
        ) : (
          "—"
        )}
      </td>
      <td>
        {row.currentCompany ? (
          <>
            {row.currentCompany.name}
            {row.currentCompany.parentName
              ? ` (${row.currentCompany.parentName})`
              : ""}{" "}
            {row.currentCompany.aiNormalized && (
              <Badge tone="info">AI đề xuất</Badge>
            )}
          </>
        ) : (
          "—"
        )}
      </td>
      <td>{row.province ?? "—"}</td>
      <td title={estimatedExperienceHint}>
        {estimatedExperience(row.totalExperienceMonths)}
      </td>
      <td>{reasons.length ? reasons.join(" · ") : "—"}</td>
    </tr>
  );
}
export function CandidateSearchResults({ filters }: { filters: string }) {
  const query = useCandidateSearch(filters);
  const seenIds = new Set<string>();
  const rows =
    query.data?.pages
      .flatMap((page) => page.content)
      .filter((row) => {
        if (seenIds.has(row.id)) return false;
        seenIds.add(row.id);
        return true;
      }) ?? [];
  return (
    <Panel
      title="Kết quả tìm kiếm"
      footer={
        !query.isPlaceholderData &&
        query.hasNextPage && (
          <Button
            variant="secondary"
            disabled={query.isFetchingNextPage}
            onClick={() => void query.fetchNextPage()}
          >
            {query.isFetchingNextPage ? "Đang tải thêm…" : "Tải thêm"}
          </Button>
        )
      }
    >
      <>
        {query.error && (
          <ErrorNotice error={query.error} retry={() => void query.refetch()} />
        )}
        {query.isPending ? (
          <Loading rows={6} />
        ) : (
          query.data &&
          (rows.length === 0 ? (
            <Empty>Không tìm thấy ứng viên phù hợp.</Empty>
          ) : (
            <div className="table-scroll">
              <table className="table">
                <thead>
                  <tr>
                    <th>Ứng viên</th>
                    <th>Chức danh hiện tại</th>
                    <th>Công ty hiện tại</th>
                    <th>Tỉnh/thành</th>
                    <th title={estimatedExperienceHint}>
                      Kinh nghiệm (dự tính)
                    </th>
                    <th>Lý do khớp</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <SearchResultRow key={row.id} row={row} />
                  ))}
                </tbody>
              </table>
            </div>
          ))
        )}
      </>
    </Panel>
  );
}
