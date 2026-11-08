import type { ReactNode } from "react";
/** Page title row: the single h1 of a screen, its context and actions. */
export function PageHeader({
  title,
  description,
  meta,
  actions,
}: {
  title: ReactNode;
  description?: ReactNode;
  meta?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="page-header">
      <div className="page-header__text">
        <div className="page-header__title">
          <h1>{title}</h1>
        </div>
        {description && (
          <p className="page-header__description">{description}</p>
        )}
        {meta && <div className="page-header__meta">{meta}</div>}
      </div>
      {actions && <div className="btn-row">{actions}</div>}
    </div>
  );
}
