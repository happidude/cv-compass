import { Link, NavLink } from "react-router-dom";
import { Compass } from "lucide-react";
import { cn } from "../../shared/utils";
import { navGroups } from "./nav";
export function Sidebar() {
  return (
    <aside className="rail">
      <Link className="brand" to="/" aria-label="CV Compass — Tổng quan">
        <span className="brand__mark" aria-hidden="true">
          <Compass size={18} />
        </span>
        <span className="brand__text" aria-hidden="true">
          CV Compass
        </span>
      </Link>
      <nav aria-label="Điều hướng chính" className="rail__nav">
        {navGroups.map((group) => (
          <div
            key={group.id}
            className={cn("nav-section", `nav-section--${group.id}`)}
          >
            <p className="nav-group__label" id={`nav-${group.id}`}>
              {group.label}
            </p>
            <ul
              className="nav-group"
              aria-labelledby={`nav-${group.id}`}
              role="list"
            >
              {group.items.map(({ label, icon: Icon, to, end }) => (
                <li key={label}>
                  {to ? (
                    <NavLink
                      className="nav-item"
                      to={to}
                      end={end}
                      title={label}
                    >
                      <Icon size={18} aria-hidden="true" />
                      <span className="nav-item__label">{label}</span>
                    </NavLink>
                  ) : (
                    <span
                      className="nav-item nav-item--soon"
                      aria-disabled="true"
                      title={`${label} — sắp có`}
                    >
                      <Icon size={18} aria-hidden="true" />
                      <span className="nav-item__label">{label}</span>
                    </span>
                  )}
                </li>
              ))}
            </ul>
          </div>
        ))}
      </nav>
      <p className="rail__footer">HR · Nội bộ</p>
    </aside>
  );
}
