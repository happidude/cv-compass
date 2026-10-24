import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { documentKeys, getDocument, listDocuments } from "./api";
import {
  activeJob,
  pollingInterval,
  usePageVisible,
} from "../../shared/polling";
export const useDocuments = () =>
  useInfiniteQuery({
    queryKey: documentKeys.lists,
    initialPageParam: null as string | null,
    queryFn: ({ pageParam, signal }) => listDocuments(pageParam, signal),
    getNextPageParam: (last) => (last.hasNext ? last.nextBeforeId : undefined),
  });
export const useDocument = (id: string, started: number) => {
  const visible = usePageVisible();
  return useQuery({
    queryKey: documentKeys.detail(id),
    queryFn: ({ signal }) => getDocument(id, signal),
    refetchInterval: (query) =>
      pollingInterval(
        started,
        Date.now(),
        activeJob(query.state.data?.processing.status ?? ""),
        query.state.fetchStatus === "fetching",
        visible,
      ),
    refetchIntervalInBackground: false,
  });
};
