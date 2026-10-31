<a id="frontend-agent-rules"></a>

# Quy tắc agent cho frontend

Kế thừa [quy tắc root](../AGENTS.md). Chỉ sửa frontend trong phạm vi task rõ ràng.
Đọc [convention frontend](../docs/conventions/frontend.md) và [README](README.md).

- Giữ React function component/hooks, TypeScript strict, Router, TanStack Query,
  Zod và semantic CSS/Tailwind hiện có. Không thêm dependency hoặc đổi cấu hình công cụ.
- Giữ ranh giới app/shared/features, public feature export và thứ tự hook.
  Server state dùng Query; UI state giữ cục bộ. Giữ thứ tự import có side effect.
- Kiểm tra dữ liệu HTTP tại boundary; giữ wire field, ID, retry/cancellation
  và cách xử lý kết quả upload không xác định. Không render OCR thành HTML hoặc lưu cục bộ.
- Không log CV/token/body, dùng fixture CV thật hoặc đưa credential production vào VITE bundle.
- Prettier: 2 spaces, double quotes, semicolons, width 80; giữ rule ESLint hiện có.
  `npm exec prettier -- --write src tests`; không cleanup hàng loạt runtime/generated asset.
- Vitest `*.test.ts(x)`, Playwright `*.spec.ts`, MSW/Testing Library, dữ liệu tổng hợp.
  Từ frontend: `npm run check` và `npm run test:browser`.
- Đọc report/số liệu và warning thực tế trên source cuối. Giữ nguyên CI hiện có.
  Xem [kiểm chứng](../docs/VERIFICATION.md); không dùng bằng chứng cũ thay check mới.
