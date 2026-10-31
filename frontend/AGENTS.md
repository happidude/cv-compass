# Quy tắc frontend

Theo [root](../AGENTS.md). Sửa TS/React/CSS đọc [convention](../docs/conventions/frontend.md);
chạy frontend: [README](README.md); đụng HTTP: [contract](../docs/contracts/README.md)
và policy theo root; lệnh/gate: [kiểm chứng](../docs/VERIFICATION.md).

- Giữ React function/hooks, TypeScript strict, Router, TanStack Query, Zod, semantic CSS/Tailwind;
  không thêm dependency/đổi tool config. Giữ app/shared/features, public export/thứ tự hook/import side effect.
  Server state dùng Query, UI state cục bộ.
- Kiểm HTTP boundary; giữ wire field/ID/retry/cancellation và upload kết quả chưa rõ.
  Không render OCR thành HTML/lưu cục bộ; không đưa credential production vào VITE bundle.
- Prettier 2 spaces/double quotes/semicolons/width 80; giữ ESLint,
  không cleanup hàng loạt runtime/generated asset.
- Vitest `*.test.ts(x)`, Playwright `*.spec.ts`, MSW/Testing Library; dữ liệu/log theo policy.
