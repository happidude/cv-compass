import { Link, NavLink, Outlet, useLocation } from "react-router-dom";
import { FileText, History } from "lucide-react";
import { config } from "../shared/config";
import { LogoutButton } from "./auth";
export function Layout() {
  const path = useLocation().pathname;
  const batches = path.startsWith("/batches");
  const candidates = path.startsWith("/candidates");
  return (
    <div className="shell">
      <a href="#main" className="skip-link">
        Đến nội dung chính
      </a>
      <aside className="sidebar">
        <Link className="brand" to="/documents">
          <span className="brand-icon">C</span> CV Compass
        </Link>
        <p className="nav-label">TUYỂN DỤNG</p>
        <nav aria-label="Điều hướng chính">
          <NavLink to="/candidates">Hồ sơ ứng viên</NavLink>
          <NavLink to="/documents">
            <FileText size={16} aria-hidden="true" />
            Quản lý CV
          </NavLink>
          <NavLink to="/batches">
            <History size={16} aria-hidden="true" />
            Lịch sử nhập
          </NavLink>
        </nav>
        <p className="sidebar-footer">HR · Nội bộ</p>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <LogoutButton />
          <span>
            CV Compass /{" "}
            <Link
              to={
                candidates ? "/candidates" : batches ? "/batches" : "/documents"
              }
            >
              {candidates
                ? "Hồ sơ ứng viên"
                : batches
                  ? "Lịch sử nhập"
                  : "Quản lý CV"}
            </Link>
            {path === "/documents/upload" && " / Upload"}
          </span>
          {config.mock && (
            <span className="badge info" role="status">
              Dữ liệu mẫu
            </span>
          )}
        </header>
        <main id="main" tabIndex={-1}>
          <Outlet />
        </main>
      </div>
    </div>
  );
}
