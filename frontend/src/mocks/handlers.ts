import { http, HttpResponse, delay } from "msw";
import { config } from "../shared/config";
import {
  details,
  metadata,
  batch,
  batches,
  items,
  documentPage,
  apiFailure,
} from "./fixtures";
const base = config.apiBase;
export const handlers = [
  http.get(`${base}/documents`, async ({ request }) => {
    await delay(150);
    return HttpResponse.json(
      new URL(request.url).searchParams.has("beforeId")
        ? { ...documentPage, content: [] }
        : documentPage,
    );
  }),
  http.get(`${base}/documents/:id`, async ({ params }) => {
    await delay(150);
    const item = details.find((d) => String(d.id) === params.id);
    return item
      ? HttpResponse.json(item)
      : HttpResponse.json(
          { ...apiFailure, code: "NOT_FOUND" },
          { status: 404 },
        );
  }),
  http.get(`${base}/batches`, async ({ request }) => {
    await delay(150);
    const page = Number(new URL(request.url).searchParams.get("page") ?? 0);
    return HttpResponse.json({
      content: page === 0 ? batches : [],
      page,
      size: 7,
      totalPages: 1,
      totalElements: batches.length,
    });
  }),
  http.get(`${base}/batches/:id`, async ({ params }) => {
    await delay(150);
    const b = batches.find((b) => String(b.id) === params.id);
    return b
      ? HttpResponse.json(b)
      : HttpResponse.json(
          { ...apiFailure, code: "NOT_FOUND" },
          { status: 404 },
        );
  }),
  http.get(`${base}/batches/:id/items`, async ({ request }) => {
    await delay(150);
    const page = Number(new URL(request.url).searchParams.get("page") ?? 0);
    return HttpResponse.json({
      content: page === 0 ? items : [],
      page,
      size: 7,
      totalPages: 1,
      totalElements: 4,
    });
  }),
  http.post(`${base}/documents`, async ({ request }) => {
    const data = await request.formData();
    const file = data.get("file");
    await delay(500);
    return HttpResponse.json(
      metadata(8, file instanceof File ? file.name : "Synthetic_uploaded.pdf"),
      { status: 201 },
    );
  }),
  http.post(`${base}/batches`, async ({ request }) => {
    const data = await request.formData();
    const files = data.getAll("files");
    await delay(500);
    return HttpResponse.json(
      {
        batch: {
          ...batch(143, "COMPLETED"),
          totalFiles: files.length,
          successCount: files.length,
          failedCount: 0,
        },
        items: files.map((f, i) => ({
          fileOrder: i,
          displayName: f instanceof File ? f.name : "Synthetic.pdf",
          documentId: 8 + i,
          errorCode: null,
        })),
      },
      { status: 201 },
    );
  }),
];
