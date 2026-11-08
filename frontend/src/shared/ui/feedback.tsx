import type { ReactNode } from "react";
import { RefreshCw } from "lucide-react";
import { RequestError, errorMessage } from "../http";
import { cn } from "../utils";
import { Button } from "./button";
import type { Tone } from "./badge";
export function Notice({
  tone = "info",
  role,
  className,
  children,
}: {
  tone?: Tone;
  role?: "alert" | "status";
  className?: string;
  children: ReactNode;
}) {
  return (
    <div className={cn("notice", `notice--${tone}`, className)} role={role}>
      {children}
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
    <div className="notice notice--bad" role="alert">
      <p>{errorMessage(error)}</p>
      {error instanceof RequestError && error.code && (
        <p className="notice__meta">Mã: {error.code}</p>
      )}
      {error instanceof RequestError && error.correlationId && (
        <p className="notice__meta">Mã tra cứu: {error.correlationId}</p>
      )}
      {retry && (
        <Button variant="secondary" size="sm" onClick={retry}>
          <RefreshCw size={14} aria-hidden="true" />
          Thử lại
        </Button>
      )}
    </div>
  );
}
/** Skeleton placeholder; the status text is kept for assistive tech. */
export function Loading({
  label = "Đang tải dữ liệu…",
  rows = 4,
}: {
  label?: string;
  rows?: number;
}) {
  return (
    <div role="status" className="skeleton">
      <span className="sr-only">{label}</span>
      {Array.from({ length: rows }, (_, i) => (
        <span key={i} className="skeleton__bar" aria-hidden="true" />
      ))}
    </div>
  );
}
export function Empty({
  children = "Chưa có dữ liệu.",
  inline = false,
}: {
  children?: ReactNode;
  inline?: boolean;
}) {
  return <p className={cn("empty", inline && "empty--inline")}>{children}</p>;
}
