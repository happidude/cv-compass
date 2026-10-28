import { createBrowserRouter, Navigate, Link } from "react-router-dom";
import { Layout } from "./layout";
import { DocumentsPage, DocumentPage, UploadPage } from "../features/documents";
import { BatchesPage, BatchPage } from "../features/batches";
import { CandidatesPage, CandidatePage } from "../features/candidates";
import { RequireUser, LoginPage } from "./auth";
export const routes = [
  { path: "/login", element: <LoginPage /> },
  {
    element: <RequireUser />,
    children: [
      {
        element: <Layout />,
        errorElement: (
          <main>
            <h1>Không thể mở trang</h1>
            <Link to="/documents">Quay về Quản lý CV</Link>
          </main>
        ),
        children: [
          { path: "/", element: <Navigate to="/documents" replace /> },
          { path: "/documents", element: <DocumentsPage /> },
          { path: "/documents/upload", element: <UploadPage /> },
          { path: "/documents/:id", element: <DocumentPage /> },
          { path: "/batches", element: <BatchesPage /> },
          { path: "/batches/:id", element: <BatchPage /> },
          { path: "/candidates", element: <CandidatesPage /> },
          { path: "/candidates/:id", element: <CandidatePage /> },
          {
            path: "*",
            element: (
              <>
                <h1>404 — Không tìm thấy trang</h1>
                <Link to="/documents">Quay về Quản lý CV</Link>
              </>
            ),
          },
        ],
      },
    ],
  },
];
export const router = createBrowserRouter(routes);
