import { Link, useMatches } from "react-router-dom";
/** A route opts in with `handle: { crumb: "Label" | (params) => "Label" }`. */
export type Crumb = string | ((params: Record<string, string>) => string);
type Handle = { crumb?: Crumb; parent?: { label: string; to: string } };
export function Breadcrumb() {
  const match = useMatches().at(-1);
  const handle = match?.handle as Handle | undefined;
  if (!match || !handle?.crumb) return null;
  const params = match.params as Record<string, string>;
  const current =
    typeof handle.crumb === "function" ? handle.crumb(params) : handle.crumb;
  return (
    <nav aria-label="Vị trí trang" className="breadcrumb">
      <ol>
        {handle.parent && (
          <li>
            <Link to={handle.parent.to}>{handle.parent.label}</Link>
          </li>
        )}
        <li>
          <span aria-current="page">{current}</span>
        </li>
      </ol>
    </nav>
  );
}
