import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Navigate, Outlet, useNavigate } from "react-router-dom";
import { currentUser, login, logout } from "../shared/session";
import { RequestError, errorMessage } from "../shared/http";

export function RequireUser() {
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
    return <Navigate to="/login" replace />;
  if (me.isPending) return <p role="status">Đang tải dữ liệu…</p>;
  if (me.error)
    return (
      <p role="alert">
        {errorMessage(me.error)}{" "}
        <button onClick={() => void me.refetch()}>Thử lại</button>
      </p>
    );
  return <Outlet />;
}
export function LoginPage() {
  const navigate = useNavigate();
  const client = useQueryClient();
  useEffect(() => {
    client.clear();
  }, [client]);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  return (
    <main className="login-panel panel">
      <h1>Đăng nhập CV Compass</h1>
      <p>Kho hồ sơ nội bộ. Liên hệ quản trị viên để được cấp tài khoản.</p>
      <form
        onSubmit={async (event) => {
          event.preventDefault();
          setBusy(true);
          setError("");
          try {
            await login(username, password);
            setPassword("");
            client.clear();
            navigate("/candidates", { replace: true });
          } catch {
            setError("Không thể đăng nhập. Kiểm tra tài khoản hoặc kết nối.");
            setPassword("");
          } finally {
            setBusy(false);
          }
        }}
      >
        <label>
          Tài khoản
          <input
            autoComplete="username"
            required
            maxLength={100}
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />
        </label>
        <label>
          Mật khẩu
          <input
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        <button disabled={busy} type="submit">
          {busy ? "Đang đăng nhập…" : "Đăng nhập"}
        </button>
        {error && <p role="alert">{error}</p>}
      </form>
    </main>
  );
}
export function LogoutButton() {
  const navigate = useNavigate();
  const client = useQueryClient();
  const [error, setError] = useState(false);
  return (
    <>
      <button
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
        Đăng xuất
      </button>
      {error && (
        <span role="alert">Không thể đăng xuất. Vui lòng thử lại.</span>
      )}
    </>
  );
}
