# Grammar 3.5

Engine **personal-info-mapping/3.5** là opt-in và **chưa nối vào runtime**:
chưa có đường chọn engine 3.5 trong dịch vụ mapping. Cấu hình
**personal-info-config-v3.5.json** (revision **personal-info-grammar-v35-r1**)
chỉ được kích hoạt ở PR wiring riêng; không kích hoạt trước đó. 3.0–3.4 giữ
nguyên hành vi, output giữ byte-for-byte. Schema kết quả và cấu hình giữ nguyên.

3.5 kế thừa toàn bộ quy tắc 3.4 (và 3.2/3.3 ở phần thông tin cá nhân), cộng
hai sửa trong grammar kinh nghiệm:

| Mẫu tổng hợp | 3.4 | 3.5 |
| --- | --- | --- |
| 1. SAMPLE TRADING (03/2018 – Present), 2) Mau Foods JSC (01/2015 – 02/2018) | Tiền tố đánh số khiến dòng bị coi là bullet, bỏ qua | Bỏ tiền tố đánh số, nhận công ty và kỳ như tiêu đề có kỳ |
| 1. Tăng doanh số 2019 – 2020 ở vùng thử nghiệm | Không tạo kinh nghiệm | Giữ nguyên: kỳ không nằm trong ngoặc, không tạo kinh nghiệm |
| Mar 2017 – Present \| GROUP SUPPLY DIRECTOR | Bị coi là ngữ cảnh vì bắt đầu bằng Group | Nhận là chức danh |
| Group Finance Director (dòng riêng giữa công ty và kỳ) | Bị coi là ngữ cảnh | Nhận là chức danh |
| Group: Sample Holdings | Ngữ cảnh | Giữ nguyên: Group chỉ là nhãn khi theo sau là dấu hai chấm |
| From: Mar-1999 To: Jun-2001 (nhãn kỳ, `To:` có dấu hai chấm) | Không nhận `To:` làm dấu nối, kỳ không được nhận | Nhận kỳ 1999-03..2001-06 |
| Feb 11 – Oct 12 Area Packaging Engineer, Sep 98 – Present | Năm hai chữ số không được nhận | Nhận khi hai đầu cùng dạng `Mon YY` (hoặc đầu cuối là Present): YY ≤ 49 → 20YY, ≥ 50 → 19YY, kèm issue TWO_DIGIT_YEAR_ASSUMED. Không nhận May 21 staff joined, Q1 23 – Q2 24, Jan 2020 – Mar 21; Oct 12 – Feb 11 là INVALID |

Bỏ tiền tố đánh số (1–2 chữ số, theo sau là dấu chấm hoặc ngoặc đơn) chỉ áp
dụng cho dòng có kỳ nằm trong ngoặc; bullet ký hiệu (•, -, \*) giữ nguyên
hành vi. Test replay 3.4 dùng digest cố định cho cùng input tổng hợp.
