import { get } from "../../shared/http";
import {
  historySchema,
  pageSchema,
  profileSchema,
  proposalsSchema,
  searchPageSchema,
  searchOptionsSchema,
  companySuggestionsSchema,
} from "./schema";
export const candidateKeys = {
  list: (before?: string) => ["candidates", before] as const,
  detail: (id: string) => ["candidate", id] as const,
  proposals: (id: string) => ["proposals", id] as const,
  history: (id: string, before?: string) => ["history", id, before] as const,
  search: (filters: string) => ["candidate-search", filters] as const,
  searchOptions: ["candidate-search-options"] as const,
  companies: (q: string) => ["candidate-search-companies", q] as const,
};
export const listCandidates = (before?: string, signal?: AbortSignal) =>
  get(
    "/candidates" + (before ? "?beforeId=" + before : ""),
    pageSchema,
    signal,
  );
export const getProfile = (id: string, signal?: AbortSignal) =>
  get("/candidates/" + id, profileSchema, signal);
export const getProposals = (id: string, signal?: AbortSignal) =>
  get("/candidates/" + id + "/proposals", proposalsSchema, signal);
export const getHistory = (id: string, before?: string, signal?: AbortSignal) =>
  get(
    "/candidates/" + id + "/history" + (before ? "?beforeId=" + before : ""),
    historySchema,
    signal,
  );
export function searchCandidates(
  filters: string,
  cursor: string | undefined,
  signal?: AbortSignal,
) {
  const params = new URLSearchParams(filters);
  if (cursor !== undefined) params.set("cursor", cursor);
  return get("/candidates/search?" + params, searchPageSchema, signal);
}
export const getSearchOptions = (signal?: AbortSignal) =>
  get("/candidates/search/options", searchOptionsSchema, signal);
export const suggestCompanies = (q: string, signal?: AbortSignal) =>
  get(
    "/candidates/search/companies?" + new URLSearchParams({ q }),
    companySuggestionsSchema,
    signal,
  );
