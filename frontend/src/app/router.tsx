import { createBrowserRouter, Navigate, Link } from "react-router-dom";
import { Layout } from "./layout";
import { DocumentsPage, DocumentPage, UploadPage } from "../features/documents";
import { BatchesPage, BatchPage } from "../features/batches";
export const routes = [
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
];
export const router = createBrowserRouter(routes);
