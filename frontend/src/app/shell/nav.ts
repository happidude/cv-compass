import type { LucideIcon } from "lucide-react";
import {
  FileOutput,
  FileText,
  History,
  LayoutDashboard,
  ListChecks,
  Search,
  Users,
} from "lucide-react";
/**
 * Navigation registry. Adding a module = one entry here + one route in
 * router.tsx + one folder in src/features. Entries without `to` are roadmap
 * items: shown as "Sắp có" and never clickable, so nobody mistakes them for
 * working features.
 */
export type NavItem = {
  label: string;
  icon: LucideIcon;
  to?: string;
  /** Match only the exact path (used for the overview at "/"). */
  end?: boolean;
};
export type NavGroup = { id: string; label: string; items: NavItem[] };
export const navGroups: NavGroup[] = [
  {
    id: "work",
    label: "Làm việc",
    items: [
      { label: "Tổng quan", icon: LayoutDashboard, to: "/", end: true },
      { label: "Hồ sơ ứng viên", icon: Users, to: "/candidates" },
      { label: "Quản lý CV", icon: FileText, to: "/documents" },
      { label: "Lịch sử nhập", icon: History, to: "/batches" },
    ],
  },
  {
    id: "soon",
    label: "Sắp có",
    items: [
      { label: "Hàng đợi review", icon: ListChecks },
      { label: "Tìm kiếm & xếp hạng", icon: Search },
      { label: "Xuất PDF", icon: FileOutput },
    ],
  },
];
