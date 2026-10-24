import { get, RequestError, upload } from "../../shared/http";
import {
  cursorSchema,
  detailSchema,
  metadataSchema,
  type Overview,
} from "./schema";
export const documentKeys = {
  all: ["documents"] as const,
  lists: ["documents", "list"] as const,
  detail: (id: string) => ["documents", "detail", id] as const,
};
export async function listDocuments(
  before: string | null,
  signal?: AbortSignal,
) {
  const result = await get(
    `/documents?size=20${before ? `&beforeId=${before}` : ""}`,
    cursorSchema,
    signal,
  );
  if (
    before &&
    result.content.some((d) => Number(d.metadata.id) >= Number(before))
  )
    throw new RequestError("contract");
  return result;
}
export const getDocument = (id: string, signal?: AbortSignal) =>
  get(`/documents/${id}`, detailSchema, signal);
export const uploadDocument = (
  file: File,
  progress: (n: number) => void,
  signal: AbortSignal,
) => {
  const body = new FormData();
  body.append("file", file);
  return upload("/documents", body, metadataSchema, progress, signal);
};
export function dedupeDocuments(pages: { content: Overview[] }[]) {
  const seen = new Set<string>();
  return pages
    .flatMap((p) => p.content)
    .filter((d) => {
      if (seen.has(d.metadata.id)) return false;
      seen.add(d.metadata.id);
      return true;
    });
}
