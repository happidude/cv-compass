import type { ReactNode } from "react";
import { RequestError, errorMessage } from "../http";
import { displayName } from "../format";
import { Button } from "./button";
export function Filename({ name }: { name: string }) {
  const safe = displayName(name);
  return (
    <bdi className="filename" title={safe}>
      {safe}
    </bdi>
  );
}
export function Heading({
  title,
  description,
  action,
}: {
  title: ReactNode;
  description?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="heading">
      <div>
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      {action}
    </div>
  );
}
export function ErrorNotice({
  error,
  retry,
}: {
  error: unknown;
  retry?: () => void;
}) {
  return (
    <div className="notice bad" role="alert">
      <p>{errorMessage(error)}</p>
      {error instanceof RequestError && error.code && <p>Mã: {error.code}</p>}
      {error instanceof RequestError && error.correlationId && (
        <p>Mã tra cứu: {error.correlationId}</p>
      )}
      {retry && (
        <Button variant="outline" onClick={retry}>
          Thử lại
        </Button>
      )}
    </div>
  );
}
export function Loading() {
  return (
    <p role="status" className="empty">
      Đang tải dữ liệu…
    </p>
  );
}
export function Empty({
  children = "Chưa có dữ liệu.",
}: {
  children?: ReactNode;
}) {
  return <p className="empty">{children}</p>;
}
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
    <div className="table-footer">
      <span>
        Trang {page + 1}
        {totalPages > 0 && ` / ${totalPages}`}
      </span>
      <div className="actions">
        <Button
          variant="outline"
          disabled={busy || page === 0}
          onClick={() => change(page - 1)}
        >
          Trước
        </Button>
        <Button
          variant="outline"
          disabled={busy || page + 1 >= totalPages}
          onClick={() => change(page + 1)}
        >
          Sau
        </Button>
      </div>
    </div>
  );
}
export function LongText({ text }: { text: string }) {
  return text.length > 1200 ? (
    <details className="long-text">
      <summary>
        Hiện thêm text ({text.length.toLocaleString("vi-VN")} ký tự)
      </summary>
      <pre>{text}</pre>
    </details>
  ) : (
    <pre>{text}</pre>
  );
}
