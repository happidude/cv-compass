import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import {
  candidateKeys,
  getHistory,
  getProfile,
  getProposals,
  listCandidates,
  searchCandidates,
  getSearchOptions,
  suggestCompanies,
} from "./api";
export const useCandidates = (before?: string) =>
  useQuery({
    queryKey: candidateKeys.list(before),
    queryFn: ({ signal }) => listCandidates(before, signal),
  });
export const useCandidateSearch = (filters: string, enabled: boolean) =>
  useInfiniteQuery({
    queryKey: candidateKeys.search(filters),
    queryFn: ({ pageParam, signal }) =>
      searchCandidates(filters, pageParam, signal),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) =>
      page.hasNext ? (page.nextCursor ?? undefined) : undefined,
    enabled,
  });
export const useSearchOptions = () =>
  useQuery({
    queryKey: candidateKeys.searchOptions,
    queryFn: ({ signal }) => getSearchOptions(signal),
  });
export const useCompanySuggestions = (q: string) =>
  useQuery({
    queryKey: candidateKeys.companies(q),
    queryFn: ({ signal }) => suggestCompanies(q, signal),
    enabled: q.trim().length > 0,
  });
export const useProfile = (id: string) =>
  useQuery({
    queryKey: candidateKeys.detail(id),
    queryFn: ({ signal }) => getProfile(id, signal),
  });
export const useProposals = (id: string) =>
  useQuery({
    queryKey: candidateKeys.proposals(id),
    queryFn: ({ signal }) => getProposals(id, signal),
  });
export const useHistory = (id: string, before?: string) =>
  useQuery({
    queryKey: candidateKeys.history(id, before),
    queryFn: ({ signal }) => getHistory(id, before, signal),
  });
