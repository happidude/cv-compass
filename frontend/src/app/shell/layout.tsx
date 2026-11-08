import { Outlet } from "react-router-dom";
import { Sidebar } from "./sidebar";
import { Topbar } from "./topbar";
export function Layout() {
  return (
    <div className="shell">
      <a href="#main" className="skip-link">
        Đến nội dung chính
      </a>
      <Sidebar />
      <div className="workspace">
        <Topbar />
        <main id="main" className="main" tabIndex={-1}>
          <Outlet />
        </main>
      </div>
    </div>
  );
}
