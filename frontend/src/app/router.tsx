import { createBrowserRouter, Link } from "react-router-dom";
import { Layout } from "./shell/layout";
import { RequireUser, LoginPage } from "./auth";
import { OverviewPage } from "../features/overview";
import { DocumentsPage, DocumentPage, UploadPage } from "../features/documents";
import { BatchesPage, BatchPage } from "../features/batches";
import {
  CandidatesPage,
  CandidatePage,
  SearchPage,
} from "../features/candidates";
import { PageHeader } from "../shared/ui";

const documents = { label: "Quản lý CV", to: "/documents" };
const batches = { label: "Lịch sử nhập", to: "/batches" };
const candidates = { label: "Hồ sơ ứng viên", to: "/candidates" };

function RouteProblem({ title }: { title: string }) {
  return (
    <>
      <PageHeader
        title={title}
        description="Đường dẫn không tồn tại hoặc trang chưa thể hiển thị."
      />
      <Link to="/">Quay về Tổng quan</Link>
    </>
  );
}

export const routes = [
  { path: "/login", element: <LoginPage /> },
  {
    element: <RequireUser />,
    children: [
      {
        element: <Layout />,
        errorElement: (
          <main className="main">
            <RouteProblem title="Không thể mở trang" />
          </main>
        ),
        children: [
          {
            path: "/",
            element: <OverviewPage />,
            handle: { crumb: "Tổng quan" },
          },
          {
            path: "/candidates",
            element: <CandidatesPage />,
            handle: { crumb: candidates.label },
          },
          {
            path: "/candidates/:id",
            element: <CandidatePage />,
            handle: {
              parent: candidates,
              crumb: (p: Record<string, string>) => `Hồ sơ #${p.id}`,
            },
          },
          {
            path: "/search",
            element: <SearchPage />,
            handle: { crumb: "Tìm kiếm & xếp hạng" },
          },
          {
            path: "/documents",
            element: <DocumentsPage />,
            handle: { crumb: documents.label },
          },
          {
            path: "/documents/upload",
            element: <UploadPage />,
            handle: { parent: documents, crumb: "Upload" },
          },
          {
            path: "/documents/:id",
            element: <DocumentPage />,
            handle: {
              parent: documents,
              crumb: (p: Record<string, string>) => `CV #${p.id}`,
            },
          },
          {
            path: "/batches",
            element: <BatchesPage />,
            handle: { crumb: batches.label },
          },
          {
            path: "/batches/:id",
            element: <BatchPage />,
            handle: {
              parent: batches,
              crumb: (p: Record<string, string>) => `Chi tiết batch ${p.id}`,
            },
          },
          {
            path: "*",
            element: <RouteProblem title="404 — Không tìm thấy trang" />,
          },
        ],
      },
    ],
  },
];
export const router = createBrowserRouter(routes);
