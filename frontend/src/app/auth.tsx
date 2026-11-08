import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Navigate,
  Outlet,
  useLocation,
  useNavigate,
  type Location,
} from "react-router-dom";
import { Compass, LogOut } from "lucide-react";
import { currentUser, login, logout } from "../shared/session";
import { RequestError } from "../shared/http";
import { Button, ErrorNotice, Loading, RouteLine } from "../shared/ui";

/** Only same-app paths are accepted as a post-login destination. */
function returnPath(state: unknown) {
  const from = (state as { from?: Location } | null)?.from;
  const path = from ? from.pathname + from.search : "/";
  return path.startsWith("/") && !path.startsWith("//") && path !== "/login"
    ? path
    : "/";
}

export function RequireUser() {
  const location = useLocation();
  const [expired, setExpired] = useState(false);
  const me = useQuery({
    queryKey: ["session"],
    queryFn: currentUser,
    retry: false,
  });
  useEffect(() => {
    const onExpired = () => {
      setExpired(true);
    };
    window.addEventListener("cv-auth-expired", onExpired);
    return () => window.removeEventListener("cv-auth-expired", onExpired);
  }, []);
  if (expired || (me.error instanceof RequestError && me.error.status === 401))
    return <Navigate to="/login" replace state={{ from: location }} />;
  if (me.isPending)
    return (
      <main className="auth__main">
        <Loading rows={3} />
      </main>
    );
  if (me.error)
    return (
      <main className="auth__main">
        <ErrorNotice error={me.error} retry={() => void me.refetch()} />
      </main>
    );
  return <Outlet />;
}
export function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const client = useQueryClient();
  useEffect(() => {
    client.clear();
  }, [client]);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  return (
    <div className="auth">
      <aside className="auth__side">
        <span className="brand">
          <span className="brand__mark" aria-hidden="true">
            <Compass size={18} />
          </span>
          CV Compass
        </span>
        <p className="auth__lede">
          Kho hồ sơ nội bộ: mỗi giá trị trên hồ sơ đều truy được về trang PDF
          gốc.
        </p>
        <RouteLine
          vertical
          className="route--on-rail"
          label="Luồng xử lý"
          stops={[
            { label: "Lưu file", state: "done", detail: "PDF gốc giữ nguyên" },
            { label: "OCR", state: "done", detail: "Tách text cục bộ" },
            {
              label: "Hồ sơ",
              state: "active",
              detail: "Mỗi giá trị kèm bằng chứng",
            },
            {
              label: "Review",
              state: "todo",
              detail: "HR xác nhận khi chưa chắc",
            },
          ]}
        />
      </aside>
      <main className="auth__main">
        <form
          className="auth__form"
          onSubmit={async (event) => {
            event.preventDefault();
            setBusy(true);
            setError("");
            try {
              await login(username, password);
              setPassword("");
              client.clear();
              navigate(returnPath(location.state), { replace: true });
            } catch {
              setError("Không thể đăng nhập. Kiểm tra tài khoản hoặc kết nối.");
              setPassword("");
            } finally {
              setBusy(false);
            }
          }}
        >
          <div className="auth__intro">
            <h1>Đăng nhập CV Compass</h1>
            <p className="muted">
              Kho hồ sơ nội bộ. Liên hệ quản trị viên để được cấp tài khoản.
            </p>
          </div>
          <label className="field">
            Tài khoản
            <input
              className="input"
              autoComplete="username"
              required
              maxLength={100}
              value={username}
              onChange={(e) => setUsername(e.target.value)}
            />
          </label>
          <label className="field">
            Mật khẩu
            <input
              className="input"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </label>
          <Button disabled={busy} type="submit">
            {busy ? "Đang đăng nhập…" : "Đăng nhập"}
          </Button>
          {error && (
            <p role="alert" className="form-error">
              {error}
            </p>
          )}
        </form>
      </main>
    </div>
  );
}
export function LogoutButton() {
  const navigate = useNavigate();
  const client = useQueryClient();
  const [error, setError] = useState(false);
  return (
    <>
      <Button
        variant="secondary"
        size="sm"
        onClick={async () => {
          try {
            await logout();
            client.clear();
            navigate("/login", { replace: true });
          } catch {
            setError(true);
          }
        }}
      >
        <LogOut size={14} aria-hidden="true" />
        Đăng xuất
      </Button>
      {error && (
        <span role="alert" className="form-error">
          Không thể đăng xuất. Vui lòng thử lại.
        </span>
      )}
    </>
  );
}
