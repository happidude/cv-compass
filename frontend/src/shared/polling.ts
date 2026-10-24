import { useEffect, useState } from "react";
import { config } from "./config";
export function pollingInterval(
  started: number,
  now: number,
  active: boolean,
  fetching: boolean,
  visible: boolean,
) {
  return active && !fetching && visible && now - started < config.pollMax
    ? config.pollInterval
    : false;
}
// Mount detail pages with key=id. This budget belongs to the resource, not each response.
export function usePollingBudget() {
  const [started, setStarted] = useState(Date.now);
  const [expired, setExpired] = useState(false);
  useEffect(() => {
    const timer = setTimeout(
      () => setExpired(true),
      Math.max(0, config.pollMax - (Date.now() - started)),
    );
    return () => clearTimeout(timer);
  }, [started]);
  return {
    started,
    expired,
    restart: () => {
      setStarted(Date.now());
      setExpired(false);
    },
  };
}
export const activeJob = (status: string) =>
  status === "PENDING" || status === "PROCESSING";
export const activeBatch = activeJob;

export function usePageVisible() {
  const [visible, setVisible] = useState(
    () => document.visibilityState !== "hidden",
  );
  useEffect(() => {
    const update = () => setVisible(document.visibilityState !== "hidden");
    document.addEventListener("visibilitychange", update);
    return () => document.removeEventListener("visibilitychange", update);
  }, []);
  return visible;
}
