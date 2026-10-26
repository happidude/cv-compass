# Convention Frontend React — quy ước chính thức

Trạng thái: **Đã duyệt ngày 26/10/2026**; phạm vi tại [README](README.md), đã áp dụng config và một type import. Dùng pattern thực tế của
`frontend/src`, không tạo generic template React mới.

## Khảo sát và điểm chưa thống nhất

Bảng dưới ghi nhận trước khi áp dụng; trạng thái cuối tại [REFACTOR_REPORT.md](REFACTOR_REPORT.md).

React function components + hooks, TypeScript strict, React Router, TanStack Query,
Zod; Tailwind v4 kết hợp CSS semantic trong `app/styles.css`, local shadcn/Radix Button.
ESLint flat config đã giới hạn dependency; Prettier có script nhưng không thấy config
Prettier riêng. Không có nhu cầu thêm framework, store, PropTypes hay formatter khác.

| ID | File thực tế | Quan sát | Chuẩn chính thức / xử lý |
| --- | --- | --- | --- |
| F01 | `frontend/src/features/documents/queries.ts` so với `upload-page.tsx` | Internal relative imports xen vị trí; thứ tự external cũng khác | Thống nhất thứ tự nhóm cho import thuần, không chạm side-effect import |
| F02 | `frontend/src/features/documents/upload-page.tsx` | UI handlers tên `add`, `send`, `reset`; callback props chung là `retry`, `change` ở `shared/ui/common.tsx` | Giữ public props; với code mới dùng `handleXxx`/`onXxx`; không mass rename vì đa số hiện có dùng tên động từ ngắn |
| F03 | `frontend/src/features/documents/api.ts`, `queries.ts` | Function declaration và arrow export cùng tồn tại | Giữ declaration cho function nhiều statement, arrow cho adapter ngắn; không chọn một syntax cho tất cả |
| F04 | `frontend/src/features/documents/upload-page.tsx` (351 dòng), `documents/pages.tsx` (298), `batches/pages.tsx` (249) | Một page xử lý nhiều trạng thái; nhiều page/component trong một file | Giữ trước; độ dài không đủ chứng minh extraction an toàn |
| F05 | `frontend/src/shared/ui/button.tsx`, `src/app/styles.css` | Utility Tailwind cùng semantic CSS | Đây là cách phối hợp có sẵn, không phải lỗi cần chuyển toàn bộ sang một cách |
| F06 | `frontend/package.json`, `eslint.config.js` | `format:check` dùng Prettier mặc định, chưa explicit config; lint không ép thứ tự import | Explicit hóa format hiện có ở Giai đoạn 2, không thêm import-sort dependency |

## Cấu trúc và dependency

| Rule | Đúng / căn cứ repo | Sai / cần tránh | Lý do |
| --- | --- | --- | --- |
| Giữ `app/`, `features/{documents,batches}/`, `shared/`, `mocks/` | Feature có `api.ts`, `schema.ts`, `queries.ts`, `pages.tsx`, `index.ts` | Tạo global `pages/services/store/types/constants` và di chuyển tất cả | Feature-oriented đang là pattern chủ đạo; không tạo tầng chưa có nhu cầu |
| app dùng feature public entrypoint; feature khác qua public entrypoint; shared độc lập | `upload-page.tsx` import `../batches`; `app/router.tsx` qua feature index | Import `../batches/api` từ documents hoặc shared import app | `eslint.config.js` đã enforce các ranh giới này |
| Co-locate type/helper/constants theo ownership | Zod/type trong feature `schema.ts`, query key trong `api.ts`, polling trong shared | Global `types.ts` gom mọi domain (minh họa) | Tìm code theo feature dễ hơn; không đổi location chỉ để giống một boilerplate |
| Không ép một component mỗi file | `shared/ui/common.tsx` chứa các UI primitives liên quan | Rename mọi file thành PascalCase chỉ vì component PascalCase | File hiện dùng lowercase/kebab-case và tên vai trò; component name khác file name |

## Naming, component và hooks

| Rule | Đúng / ví dụ | Sai / cần tránh | Lý do |
| --- | --- | --- | --- |
| Component/type PascalCase, hook `useXxx`, biến/function camelCase; file lowercase/kebab-case | `UploadPage`, `Entry`, `useDocument`, `upload-page.tsx` | `upload_page` component, `UseDocument` hook (minh họa) | Pattern repo; không bắt component file thành `UploadPage.tsx` |
| Named export cho app code; default giữ cho config yêu cầu | `export function Button`, `export const useDocuments`; Vite config `export default` | Chuyển app sang default export đồng loạt | Named export là pattern chủ đạo, config có yêu cầu công cụ riêng |
| Handler code mới `handleUpload`, prop callback mới `onUpload`; giữ tên hiện hữu | Giữ `send`/`retry` hiện tại; tên theo convention cho feature mới `handleSend` | Đổi public prop `retry` thành `onRetry` trong đợt format | `handleXxx` chưa là pattern phổ biến; không áp đặt bằng rename diện rộng |
| Function component + hook top-level | `UploadPage` gọi state/ref/query hooks trong function | Gọi hook trong điều kiện/callback (minh họa) | React Rules of Hooks; nếu thấy vi phạm hiện hữu thì báo cáo bug riêng |
| Trật tự đọc gợi ý: hooks/state/ref → effect → handler/derived value → JSX | Giữ thứ tự hook/effect thực tế của `UploadPage` | Hoist effect hoặc reorder hook để giống template | Quy tắc cho code mới, không dùng làm lý do thay execution order |
| Function nhiều statement dùng declaration; expression adapter ngắn có thể arrow | `listDocuments(...)` và `getDocument = (...) => get(...)` | Chuyển tất cả sang arrow/declaration | Hai dạng có vai trò rõ trong repo; hoisting/name có thể khác |
| Props dùng TS type/inferred type; không thêm PropTypes | `React.ComponentProps<"button">`, `VariantProps` trong Button | `any`, runtime PropTypes mới (minh họa) | TS strict đã có; giữ optional/default prop values |

## API, state, loading/error và style

| Rule | Đúng / căn cứ repo | Sai / cần tránh | Lý do |
| --- | --- | --- | --- |
| Transport tập trung ở shared, API/schema/query trong feature | `shared/http.ts` → `documents/api.ts` → `queries.ts` | Component gọi `fetch` trực tiếp hoặc nội bộ worker endpoint | Có decoding/error/cancellation contract sẵn |
| Server state qua TanStack Query; UI state local | `queryClient` trong providers, `useState` upload; router giữ pagination | Thêm Redux/Zustand hoặc localStorage lưu OCR | Không có nhu cầu store mới; bảo mật text cá nhân |
| Giữ retry/query key/polling/cancellation hiện có | `shouldRetry`, `usePageVisible`, `pollingInterval` | “Đồng nhất” upload retry theo GET | Query/upload side-effect khác nhau; đây là behavior |
| Dùng shared UI cho loading/error/empty hiện có | `Loading`, `Empty`, `ErrorNotice`, `Status` | Render raw server error message hoặc tạo notification framework | Tên/mã/correlation an toàn đã được xử lý tập trung |
| Dùng Zod ở response boundary; giữ JSON field và ID conversion | `metadataSchema`, `decode`, safe ID → string | Cast `as DTO` thay schema parse hoặc rename response key | Validation là runtime behavior, không phải type-only cleanup |
| Giữ phối hợp semantic CSS + Tailwind utilities | `className="panel"` và `buttonVariants/cn` | Đổi mọi class sang Tailwind, reorder CSS declarations tự động | Cascade, utility conflicts và computed styles có thể đổi |
| Render OCR như text, không thêm HTML rendering | Giữ JSX text/rawText hiện có | `dangerouslySetInnerHTML` cho OCR | Không thay cách hiển thị dữ liệu hoặc mở đường XSS |

## Format và imports

| Rule | Đúng | Sai / cần tránh | Lý do |
| --- | --- | --- | --- |
| Giữ Prettier hiện có: 2 spaces, double quotes, semicolon, trailing comma, width 80 | `api.ts`, `button.tsx` | Thêm formatter thứ hai hoặc đổi single quotes toàn app | Style source hiện tại khớp mặc định Prettier; explicit hóa giúp tránh drift |
| Giữ ESLint flat config và boundary/React hooks rules | `npm run lint`, `format:check` | Tắt rule hoặc thêm eslint-disable để refactor pass | Không suy yếu checks |
| Import thuần: external → shared → feature public → same-feature; `import type` khi phù hợp | `import type { ReactNode }` trong providers; type specifier inline cũng hợp lệ | Auto-sort CSS/bootstrap imports trong `main.tsx` | Thứ tự nhóm đã duyệt để nhất quán; không đánh đổi side-effect order |
| Giữ các directive và dynamic imports | Font/style import và MSW startup trước router ở `main.tsx` | Chuyển dynamic import thành static import cho gọn | Module evaluation và mock startup có hành vi thực |
| Không auto-sort className hoặc sửa JSX whitespace có thể thấy trên UI | Giữ literal và text boundaries; preview rendered output nếu formatter động tới JSX text | Sắp class theo alphabet hay trim chuỗi UI | Cần bảo toàn appearance, accessibility và thông báo hiện tại |

Đã thêm `.prettierrc.json` và `.prettierignore` bằng **Prettier 3.6.2 đã có**, giữ
version/lockfile, không thêm prettier plugin. Từ frontend/, chạy
`npm exec prettier -- --write src tests` và `npm run format:check`; không có script
`npm run format` trong package.json, không tự thêm script. Import cleanup là đợt khác
format, không dùng eslint --fix diện rộng cho logic/React hooks. Không format
`dev-public/mockServiceWorker.js`, dist, node_modules, reports, lockfile.

## Test và quyết định đã chốt

| Rule | Đúng / căn cứ repo | Sai / cần tránh | Lý do |
| --- | --- | --- | --- |
| Giữ Vitest `*.test.ts(x)`, Playwright `*.spec.ts`; mô tả hành vi | `tests/http.test.ts`, `tests/browser/frontend.spec.ts` | Đổi suffix hoặc sửa test để phù hợp formatter mới | Discovery và assertions đang là safety net |
| Test hành vi người dùng/API với synthetic fixture, MSW/Testing Library | `contracts.test.ts`, `upload.test.ts`, `ocr.test.tsx` | Snapshot implementation rồi xóa test failure | Không sửa test để che runtime difference |
| Giữ tách unit/mock browser, smoke cô lập và live local | `npm run check`, `npm run test:browser`; config smoke/local riêng | Cho live test chạy trên dữ liệu runtime để lấy PASS | README yêu cầu fixture/môi trường đúng; credentials không vào VITE bundle |

Giữ nguyên quyết định không tách `UploadPage`, không mass rename handler hoặc di chuyển page: hook order,
component identity, focus/dialog lifecycle, callbacks, abort và query invalidation đều
có thể bị ảnh hưởng. Không coi file dài là bug và không thêm component mới trong đợt
form khi chưa chứng minh tương đương. Baseline và sau áp dụng: `npm run check` PASS
với 22 unit tests, `npm run test:browser` PASS với 30 browser tests; emitted JS của
type-import diff giống hệt. Chi tiết/bỏ qua tại [REFACTOR_REPORT.md](REFACTOR_REPORT.md).

Căn cứ chính thức: [React Rules of Hooks](https://react.dev/reference/eslint-plugin-react-hooks/lints/rules-of-hooks),
[Prettier options](https://prettier.io/docs/options),
[Prettier configuration](https://prettier.io/docs/configuration).
Folder/export/handler policies là lựa chọn dự án từ repo, không phải quy định bắt buộc React.
