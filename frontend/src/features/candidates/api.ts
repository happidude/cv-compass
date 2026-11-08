import { get } from "../../shared/http";
import {
  historySchema,
  pageSchema,
  profileSchema,
  proposalsSchema,
} from "./schema";
export const candidateKeys = {
  list: (before?: string) => ["candidates", before] as const,
  detail: (id: string) => ["candidate", id] as const,
  proposals: (id: string) => ["proposals", id] as const,
  history: (id: string, before?: string) => ["history", id, before] as const,
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
