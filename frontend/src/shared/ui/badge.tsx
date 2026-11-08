import type { ReactNode } from "react";
import { cn } from "../utils";
export type Tone = "neutral" | "info" | "good" | "warn" | "bad";
export function Badge({
  tone = "neutral",
  mark = false,
  title,
  className,
  children,
}: {
  tone?: Tone;
  mark?: boolean;
  title?: string;
  className?: string;
  children: ReactNode;
}) {
  return (
    <span className={cn("badge", `badge--${tone}`, className)} title={title}>
      {mark && <span className="badge__mark" aria-hidden="true" />}
      {children}
    </span>
  );
}
