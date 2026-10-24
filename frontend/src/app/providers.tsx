import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { shouldRetry } from "../shared/http";
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: shouldRetry,
      retryDelay: (n) => Math.min(500 * 2 ** n, 4000),
      staleTime: 15000,
      gcTime: 300000,
      refetchOnWindowFocus: false,
    },
    mutations: { retry: false },
  },
});
export function Providers({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}
