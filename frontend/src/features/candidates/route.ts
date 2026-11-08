import type { RouteStop } from "../../shared/ui";
import { stateLabel } from "./labels";
import type { Profile, Proposal } from "./schema";
/** Review route of one profile, derived only from the loaded profile data. */
export function profileRoute(
  profile: Profile,
  proposals: Proposal[] | undefined,
): RouteStop[] {
  const total = profile.experiences.length;
  const confirmed = profile.experiences.filter(
    (e) => e.state === "CONFIRMED",
  ).length;
  return [
    { label: "Hồ sơ", state: "done", detail: "Đã publish" },
    {
      label: "Thông tin cơ bản",
      state:
        profile.basicState === "CONFIRMED"
          ? "done"
          : profile.basicState === "EDITED"
            ? "active"
            : "waiting",
      detail: stateLabel(profile.basicState),
    },
    {
      label: "Kinh nghiệm",
      state: !total
        ? "todo"
        : confirmed === total
          ? "done"
          : confirmed
            ? "active"
            : "waiting",
      detail: total ? `${confirmed}/${total} đã xác nhận` : "Chưa có",
    },
    proposals === undefined
      ? { label: "Đề xuất", state: "unknown", detail: "Chưa tải được" }
      : proposals.length
        ? {
            label: "Đề xuất",
            state: "attention",
            detail: `${proposals.length} đề xuất đang mở`,
          }
        : { label: "Đề xuất", state: "done", detail: "Không còn đề xuất" },
  ];
}
