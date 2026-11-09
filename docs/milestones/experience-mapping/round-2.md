# Mapping kinh nghiệm — đợt 2 (CV nhiều trang, ngày tháng, nhãn)

Bổ sung cho [spec](spec.md). Nguồn: chủ dự án gửi thêm 8 CV thật trong chat 08/11/2026 23:11 và yêu cầu
lên kế hoạch cải thiện, nhất là CV có phần kinh nghiệm kéo dài nhiều trang. **Trạng thái: chờ duyệt**
(merge PR docs chứa file này = duyệt E5–E7; E8 cần duyệt riêng).

## Đo nhanh trên 12 CV thật (engine 3.4 tại head PR #35, đếm tay, không lưu CV)

Text trích giống worker (pdfplumber `extract_text(layout=False)`); trang scan không chạy OCR nên có thể thiếu.

| Nhóm bố cục | Số CV | Kinh nghiệm thực tế | Đúng công ty + kỳ | Nguyên nhân chính |
| --- | --- | --- | --- | --- |
| Dòng tiêu đề 1 dòng có dấu `\|`, `/`, `–` | 3 | 23 | 9 | Tách theo gạch ngang (H2), dòng ≥ 3 phần, học vấn lọt vào (H1) |
| Kỳ + công ty, mô tả dài nhiều trang | 4 | 29 | 17 | Ngắt trang đóng khối; định dạng ngày `2023 Oct`, `Feb – 2017`; heading `Experience in Details` không được nhận; nhãn `Company:` giữa câu làm mất chức danh |
| Nhãn `Period:/Employer:/Position:` | 2 | 18 | 8 | Kỳ nằm trước công ty bị khối rác "…company" ăn mất; tháng viết tắt lạ (`Decem`); chức danh là câu "Work as … in …" |
| 2 cột (text bị trộn thứ tự) | 3 | 15 | 1 | Thứ tự đọc của worker; parser không tự sửa được |
| **Tổng** | **12** | **≈ 85** | **≈ 35 (≈ 40%)** | |

5/8 CV mới có phần kinh nghiệm dài 3–8 trang. Kinh nghiệm bị mất hoặc sai nhiều nhất ở chỗ ngắt trang
(`UNLINKED_PAGE_TEXT` xuất hiện ở 4 CV) và ở định dạng ngày.

## Slice đợt 2 (sau khi sửa xong review chuỗi #34–#37)

| Slice | Mức | Nội dung | Tiêu chí chấp nhận (dữ liệu tổng hợp) |
| --- | --- | --- | --- |
| **E5** Ngắt trang | 3 | (a) Ở 3.4, khối kinh nghiệm đang mở **đi tiếp sang trang sau** khi các dòng đầu trang mới (sau khi bỏ số trang, header/footer lặp, tên ứng viên lặp, heading section lặp lại giống section hiện tại) không phải dòng tiêu đề mới; mô tả, chức danh, kỳ ở đầu trang mới gắn vào khối đó, kèm issue `PAGE_CONTINUATION_ASSUMED`. (b) Heading section giống hệt lặp ở đầu trang (`EXPERIENCE IN DETAILS` mỗi trang) không đóng khối. (c) Trang lỗi/không xử lý ở giữa vẫn chặn nối như cũ. | CV giả 4 trang: công ty + kỳ ở cuối trang 1, chức danh đầu trang 2 → một kinh nghiệm đủ 3 field; mô tả trải 3 trang → một khối; trang 2 lỗi OCR → không nối, có issue; 3.0–3.3 không đổi. |
| **E6** Heading + ngày | 3 | (a) Heading linh hoạt: dòng ngắn (≤ 6 từ, không số) chứa `experience/employment/work history/career history/kinh nghiệm/quá trình công tác` → section kinh nghiệm; nếu có thêm `summary/overview/tóm tắt` → section tóm tắt. Thêm alias config `Experience in Summary`, `Experience in Details`, `Employment History`, `Career History`, `Working History`, `Experiences`, `Quá trình công tác`. (b) Ngày: `2023 Oct`, `2012 Sep – 2018 Dec`, `2000 June- 2001 Mar`, `Feb – 2017`, `Sep’- 2010`, `Tháng 3/ 2020`, `From … to/until …`, `present` chữ thường; tháng viết tắt theo **tiền tố của tên đầy đủ** (`Decem`, `Septem`, `Octo`) được chấp nhận; sai chính tả thật (`Arp`) vẫn INVALID. | Mỗi định dạng có test; summary + detail của cùng công ty gộp 1 dòng (logic dedup hiện có); không đổi 3.0–3.3. |
| **E7** Nhãn + câu | 3 | (a) Nhãn `Company:/Position:/Period:` chỉ tính khi ở đầu dòng (sau bullet); `Position and experiences in X Company:` không phải nhãn. (b) Nhãn ngữ cảnh `Location:/Category:/Report to:/Industry:/Địa chỉ:` không thành công ty/mô tả tiêu đề. (c) Giá trị chức danh dạng câu `Work(ing) as X in Y` → title `X`, raw đầy đủ giữ trong alternatives. (d) Câu `Worked at X from A to B` rồi `Position: Y` trong 3 dòng kế → title của khối đó. (e) Dòng `A – B \| C \| kỳ`: phần có hậu tố pháp lý/đứng trước kỳ là company, phần chứa từ chức danh là title; `Earlier Career – X \| kỳ` → company X, title NOT_FOUND. | Mỗi mẫu có test tổng hợp; ExperienceEvaluationTests không giảm. |
| **E8** 2 cột (worker) | 3 | Đề xuất: worker phát hiện trang 2 cột bằng tọa độ chữ (`extract_words`), xuất text theo từng cột, kèm profile revision OCR mới và cờ layout. Cần OCR lại CV cũ. | **Chưa duyệt** — cần plan riêng (đụng hợp đồng OCR, worker offline). |
| **E-eval** Đo local | 2 | Lệnh dev chỉ chạy trên máy chủ dự án: đọc thư mục text/JSON trích từ CV thật (biến môi trường), chạy engine 3.4, **chỉ in số tổng hợp** (số kinh nghiệm, có công ty/kỳ/chức danh, tổng tháng) — không in nội dung. Không chạy trong CI, không commit dữ liệu. | Chạy được trên fixture tổng hợp; output không chứa text CV. |

Đề xuất thứ tự: sửa review #34–#37 → E5 → E6 → E7 → E-eval. E8 làm khi chủ dự án duyệt.
Ước tính sau E5–E7: nhóm 1–3 đạt khoảng 70–80% đúng công ty + kỳ; nhóm 2 cột chỉ cải thiện sau E8.

## Open Questions

1. Chấp nhận tháng viết tắt theo tiền tố (`Decem` = December)? Spec cũ ghi "không sửa tên tháng sai chính tả";
   đây là cắt ngắn chứ không phải sửa lỗi. Khuyến nghị: chấp nhận, kèm issue `MONTH_ABBREVIATION_NONSTANDARD`.
2. E8 (CV 2 cột): làm ngay sau E7 hay để sau? 3/12 CV thử là 2 cột, phần kinh nghiệm gần như không dùng được.
3. Câu ứng viên tự ghi số năm ("Hơn 14 năm kinh nghiệm", "8+ years") có muốn hiển thị cạnh tổng tính được để HR
   đối chiếu không? (ngoài phạm vi hiện tại; làm nhỏ ở E9 nếu muốn).
