import { ChevronLeft, ChevronRight } from "lucide-react";
import { Button } from "./button";
export function Pagination({
  page,
  totalPages,
  busy,
  change,
}: {
  page: number;
  totalPages: number;
  busy: boolean;
  change: (n: number) => void;
}) {
  return (
    <div className="panel__footer">
      <span className="nums">
        Trang {page + 1}
        {totalPages > 0 && ` / ${totalPages}`}
      </span>
      <div className="btn-row">
        <Button
          variant="secondary"
          size="sm"
          disabled={busy || page === 0}
          onClick={() => change(page - 1)}
        >
          <ChevronLeft size={14} aria-hidden="true" />
          Trước
        </Button>
        <Button
          variant="secondary"
          size="sm"
          disabled={busy || page + 1 >= totalPages}
          onClick={() => change(page + 1)}
        >
          Sau
          <ChevronRight size={14} aria-hidden="true" />
        </Button>
      </div>
    </div>
  );
}
