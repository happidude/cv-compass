---
name: CV Compass
description: Hub nội bộ quản lý CV — hướng thị giác "Bản đồ khảo sát"
colors:
  rail: "#12302c"
  rail-raised: "#1c443e"
  rail-text: "#e3ece8"
  rail-muted: "#9fbab3"
  ground: "#f2f4ef"
  surface: "#ffffff"
  surface-sunken: "#f7f8f5"
  ink: "#17211f"
  ink-2: "#46544f"
  ink-3: "#5a6863"
  line: "#dde3db"
  line-strong: "#c3ccc2"
  accent: "#1b5d54"
  accent-hover: "#154b44"
  accent-soft: "#e1eeea"
  focus: "#0f7a6c"
  good: "#2c6a37"
  good-soft: "#e3efe1"
  warn: "#8a4a0c"
  warn-soft: "#fbedd5"
  warn-mark: "#c27618"
  bad: "#b0281f"
  bad-soft: "#fbe5e1"
  info: "#1d5784"
  info-soft: "#e2edf6"
  highlight: "#fff1b8"
typography:
  title:
    fontFamily: "Inter, Segoe UI, system-ui, sans-serif"
    fontSize: "1.5rem"
    fontWeight: 600
    lineHeight: 1.3
    letterSpacing: "-0.02em"
  section:
    fontFamily: "Inter, Segoe UI, system-ui, sans-serif"
    fontSize: "1rem"
    fontWeight: 600
    lineHeight: 1.3
  body:
    fontFamily: "Inter, Segoe UI, system-ui, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.55
  label:
    fontFamily: "Inter, Segoe UI, system-ui, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 500
    lineHeight: 1.3
rounded:
  sm: "4px"
  md: "6px"
  lg: "10px"
  pill: "999px"
spacing:
  "1": "4px"
  "2": "8px"
  "3": "12px"
  "4": "16px"
  "5": "20px"
  "6": "24px"
  "8": "32px"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.surface}"
    rounded: "{rounded.md}"
    height: "36px"
    padding: "0 16px"
  button-primary-hover:
    backgroundColor: "{colors.accent-hover}"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
    height: "36px"
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
    height: "36px"
  nav-item-active:
    backgroundColor: "{colors.rail-raised}"
    textColor: "{colors.surface}"
    rounded: "{rounded.md}"
  evidence-ref:
    backgroundColor: "{colors.accent-soft}"
    textColor: "{colors.accent-hover}"
    rounded: "{rounded.sm}"
    height: "24px"
---

# Design System: CV Compass

Nguồn sự thật của token là `src/app/styles/tokens.css`; file này mô tả ý đồ và quy tắc dùng.
Bối cảnh sản phẩm ở [PRODUCT.md](PRODUCT.md).

## Overview

CV Compass đọc như một **tờ bản đồ khảo sát được chú giải**: mỗi CV là một tuyến đi qua các trạm
(Lưu file → OCR → Hồ sơ → Review), mỗi giá trị trên hồ sơ có "toạ độ" trong PDF gốc (trang, đoạn).
Đây là màn hình làm việc (Operate): bố cục, điều hướng và control đều là chuẩn quen thuộc của app quản trị;
"thế giới" chỉ đóng góp màu, chữ, mật độ và một dấu ấn — **tuyến xử lý**.

Người dùng: 1–3 HR làm việc ban ngày trong văn phòng ⇒ chỉ giao diện sáng. Màu viết bằng token để
sau này thêm theme tối chỉ cần định nghĩa lại `tokens.css`.

## Colors

- **Rail (mực khảo sát)** `#12302c`: thanh điều hướng trái, mặt bên màn đăng nhập. Là vùng màu "cam kết"
  của shell, không dùng ở nơi khác.
- **Nền giấy bản đồ** `#f2f4ef` (xanh xám rất nhạt, không phải kem); bề mặt làm việc trắng `#ffffff`.
- **Đường đồng mức** `#dde3db` / `#c3ccc2`: viền panel, kẻ bảng, đoạn tuyến chưa đi.
- **Accent (xanh khảo sát)** `#1b5d54`: chỉ cho hành động chính, lựa chọn hiện tại, chip evidence.
- **Ký hiệu trạng thái** (luôn đi kèm chữ, không dùng màu đơn lẻ để truyền nghĩa):
  xanh rừng = xong/thành công, đất son = một phần/cần chú ý, đỏ khảo sát = lỗi, xanh nước = đang chạy,
  xám chì = chưa có / không xác định.
- **Highlight** `#fff1b8`: quote evidence trong khung PDF nguồn.

Tương phản: chữ thường ≥ 4.5:1 trên nền trắng và nền giấy (axe kiểm ở 1440/1024/768 px).

## Typography

Một họ chữ: **Inter** (đã có sẵn, hỗ trợ tiếng Việt), fallback Segoe UI/system-ui. Thang cố định rem,
tỉ lệ ~1.2: 12 · 13 · 14 (body) · 16 (tiêu đề section) · 20 · 24 (h1). Số liệu, ngày, ID dùng
`font-variant-numeric: tabular-nums`. Chỉ có weight 400/500/600/700 vì font tĩnh.

## Layout

- Shell: rail 232 px + vùng làm việc; topbar 56 px dính trên cùng (breadcrumb, nhãn "Dữ liệu mẫu",
  tài khoản, đăng xuất); nội dung tối đa 1480 px, lề 32 px.
- Breakpoint: `< 1100 px` rail thu gọn còn icon (68 px), lưới 2 cột về 1 cột;
  `< 768 px` rail thành thanh ngang trên cùng, ẩn mục "Sắp có", tuyến xử lý chuyển dọc.
- Không được có cuộn ngang trang ở mọi breakpoint (test kiểm `scrollWidth`).
- Khoảng cách theo bước 4 px (`--sp-*`), nhóm chặt bên trong, tách rộng giữa các nhóm.

## Elevation & Depth

Phẳng, phân lớp bằng viền đồng mức. `--shadow-1` (1 px, rất nhẹ) cho panel; `--shadow-2` chỉ cho dialog.
Không dùng glow, glass trang trí hay bóng lệch cứng.

## Shapes

Bo góc 4 px (chip, ô nhỏ) · 6 px (nút, input) · 10 px (panel) · pill cho badge. Dấu chấm tròn là "trạm";
trạm lỗi là hình thoi (xoay 45°) để phân biệt không cần màu.

## Components

Tất cả ở `src/shared/ui` (import qua `shared/ui/index.ts`):

- **Button** `primary | secondary | ghost | danger`, cỡ `md | sm`, `icon`. Mặc định `type="button"`.
- **Badge / Status**: chấm + chữ; `Status` ánh xạ enum API sang nhãn tiếng Việt, enum lạ → "Không xác định".
- **Panel**: header (h2 + phụ đề + action) / body / footer. **PageHeader**: h1 duy nhất của trang.
- **Notice / ErrorNotice / Loading (skeleton) / Empty**: trạng thái chuẩn; `ErrorNotice` mới có `role="alert"`.
- **Pagination, Segmented (lọc danh sách đang hiển thị), CountBar (tỉ lệ thành công/thất bại của batch)**.
- **RouteLine — dấu ấn**: tuyến xử lý; biến thể đầy đủ (có nhãn), `compact` (chỉ hình, luôn đứng cạnh
  `Status` chứa chữ) và `vertical`. Trạm không có dữ liệu API dùng `unknown` (nét đứt) — không đoán.
- **EvidenceButton** (feature candidates): chip toạ độ "Tr.3 · Đ.1"; tên truy cập vẫn là
  "Đối chiếu … trang 3, đoạn 1".
- **Navigation**: registry ở `src/app/shell/nav.ts`; mục không có `to` hiển thị trong nhóm "Sắp có",
  `aria-disabled`, không bấm được.

## Do's and Don'ts

**Nên**

- Mỗi giá trị suy ra từ dữ liệu: hiển thị kèm nguồn, trạng thái review hoặc toạ độ evidence.
- Đếm/lọc chỉ trên bản ghi đã tải và ghi rõ phạm vi ("Trong 7 CV mới nhất").
- Thêm module = 1 mục trong `nav.ts` + 1 route trong `router.tsx` + 1 thư mục `src/features/<module>`.
- Dùng token CSS; style tái sử dụng đặt trong `components.css`, bố cục màn hình trong `screens.css`.

**Không nên**

- Không bịa số tổng/KPI khi API chưa có (không làm "hero metric").
- Không dùng màu trạng thái làm trang trí, không gradient chữ, không viền trái màu dày cho card/alert.
- Không thêm eyebrow/kicker phía trên tiêu đề; không lồng card trong card.
- Không render OCR/text CV thành HTML.
