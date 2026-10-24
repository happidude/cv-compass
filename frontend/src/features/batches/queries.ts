import { useQuery } from "@tanstack/react-query";
import { batchKeys, listBatches, getBatch, getItems } from "./api";
import {
  activeBatch,
  pollingInterval,
  usePageVisible,
} from "../../shared/polling";
export const useBatches = (page: number) =>
  useQuery({
    queryKey: batchKeys.list(page),
    queryFn: ({ signal }) => listBatches(page, signal),
  });
export const useBatch = (id: string, started: number) => {
  const visible = usePageVisible();
  return useQuery({
    queryKey: batchKeys.detail(id),
    queryFn: ({ signal }) => getBatch(id, signal),
    refetchInterval: (q) =>
      pollingInterval(
        started,
        Date.now(),
        activeBatch(q.state.data?.status ?? ""),
        q.state.fetchStatus === "fetching",
        visible,
      ),
    refetchIntervalInBackground: false,
  });
};
export const useItems = (id: string, page: number) =>
  useQuery({
    queryKey: batchKeys.items(id, page),
    queryFn: ({ signal }) => getItems(id, page, signal),
  });
