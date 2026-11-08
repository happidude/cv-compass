import { useQuery } from "@tanstack/react-query";
import {
  candidateKeys,
  getHistory,
  getProfile,
  getProposals,
  listCandidates,
} from "./api";
export const useCandidates = (before?: string) =>
  useQuery({
    queryKey: candidateKeys.list(before),
    queryFn: ({ signal }) => listCandidates(before, signal),
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
