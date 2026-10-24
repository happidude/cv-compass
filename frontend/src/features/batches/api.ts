import { get, upload } from "../../shared/http";
import {
  batchSchema,
  batchPageSchema,
  itemPageSchema,
  importSchema,
} from "./schema";
export const batchKeys = {
  all: ["batches"] as const,
  list: (page: number) => ["batches", "list", page] as const,
  detail: (id: string) => ["batches", "detail", id] as const,
  items: (id: string, page: number) => ["batches", "items", id, page] as const,
};
export const listBatches = (page: number, signal?: AbortSignal) =>
  get(`/batches?page=${page}&size=20`, batchPageSchema, signal);
export const getBatch = (id: string, signal?: AbortSignal) =>
  get(`/batches/${id}`, batchSchema, signal);
export const getItems = (id: string, page: number, signal?: AbortSignal) =>
  get(`/batches/${id}/items?page=${page}&size=20`, itemPageSchema, signal);
export const uploadBatch = (
  files: File[],
  progress: (n: number) => void,
  signal: AbortSignal,
) => {
  const body = new FormData();
  files.forEach((file) => body.append("files", file));
  return upload("/batches", body, importSchema, progress, signal);
};
