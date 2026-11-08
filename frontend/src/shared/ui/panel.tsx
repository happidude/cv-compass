import type { ReactNode } from "react";
import { cn } from "../utils";
/** Bordered work surface with an optional titled header and footer. */
export function Panel({
  title,
  subtitle,
  actions,
  footer,
  padded = false,
  as: Tag = "section",
  className,
  labelledBy,
  children,
}: {
  title?: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  footer?: ReactNode;
  padded?: boolean;
  as?: "section" | "article" | "div";
  className?: string;
  labelledBy?: string;
  children?: ReactNode;
}) {
  return (
    <Tag className={cn("panel", className)} aria-labelledby={labelledBy}>
      {(title || actions) && (
        <header className="panel__header">
          <div>
            {title && <h2 id={labelledBy}>{title}</h2>}
            {subtitle && <p className="panel__subtitle">{subtitle}</p>}
          </div>
          {actions && <div className="btn-row">{actions}</div>}
        </header>
      )}
      {padded ? <div className="panel__body">{children}</div> : children}
      {footer && <footer className="panel__footer">{footer}</footer>}
    </Tag>
  );
}
