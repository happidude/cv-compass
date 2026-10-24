import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { RouterProvider } from "react-router-dom";
import "@fontsource/inter/latin-400.css";
import "@fontsource/inter/latin-500.css";
import "@fontsource/inter/latin-600.css";
import "@fontsource/inter/latin-700.css";
import "@fontsource/inter/vietnamese-400.css";
import "@fontsource/inter/vietnamese-500.css";
import "@fontsource/inter/vietnamese-600.css";
import "@fontsource/inter/vietnamese-700.css";
import "./app/styles.css";
async function start() {
  const { config } = await import("./shared/config");
  if (import.meta.env.DEV && config.mock) {
    const { worker } = await import("./mocks/browser");
    await worker.start({ quiet: true, onUnhandledRequest: "error" });
  }
  const { Providers } = await import("./app/providers");
  const { router } = await import("./app/router");
  createRoot(document.getElementById("root")!).render(
    <StrictMode>
      <Providers>
        <RouterProvider router={router} />
      </Providers>
    </StrictMode>,
  );
}
void start().catch(() => {
  document.getElementById("root")!.textContent =
    "Cấu hình không hợp lệ hoặc ứng dụng chưa thể khởi động. Vui lòng kiểm tra cấu hình công khai.";
});
