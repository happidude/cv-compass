import { useQuery } from "@tanstack/react-query";
import { config } from "../../shared/config";
import { currentUser } from "../../shared/session";
import { Badge } from "../../shared/ui";
import { LogoutButton } from "../auth";
import { Breadcrumb } from "./breadcrumb";
export function Topbar() {
  // Same key as RequireUser, so this reads the cached session.
  const me = useQuery({
    queryKey: ["session"],
    queryFn: currentUser,
    retry: false,
  });
  const name = me.data?.username;
  return (
    <header className="topbar">
      <Breadcrumb />
      <div className="topbar__end">
        {config.mock && <Badge tone="info">Dữ liệu mẫu</Badge>}
        {name && (
          <span className="user-chip">
            <span className="user-chip__avatar" aria-hidden="true">
              {name.slice(0, 2)}
            </span>
            <span className="user-chip__name">{name}</span>
          </span>
        )}
        <LogoutButton />
      </div>
    </header>
  );
}
