import { useSearchParams } from "react-router-dom";
import { Empty, PageHeader, Panel } from "../../shared/ui";
import { CandidateSearchForm } from "./search-form";
import { searchFilters } from "./search-filters";
import { CandidateSearchResults } from "./search-results";

export function SearchPage() {
  const [params] = useSearchParams();
  const filters = searchFilters(params).toString();
  return (
    <>
      <PageHeader
        title="Tìm kiếm & xếp hạng"
        description="Tìm ứng viên theo từ khóa, công ty, chức danh và nhóm lọc."
      />
      <CandidateSearchForm />
      {filters ? (
        <CandidateSearchResults filters={filters} />
      ) : (
        // No condition yet: never load the whole corpus as a fallback list.
        <Panel>
          <Empty>Nhập từ khóa hoặc chọn bộ lọc để tìm ứng viên</Empty>
        </Panel>
      )}
    </>
  );
}
