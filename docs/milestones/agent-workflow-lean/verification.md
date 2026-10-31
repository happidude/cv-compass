# Kiểm chứng workflow agent gọn

- Spec: [phê duyệt và acceptance](spec.md); base/main `7feddfbc737f0946fa815d9b1d5c03ecfd1184b9`.
- Branch `chore/agent-workflow-lean`, checkout `E:/cv-compass`; source cuối định danh tại PR.
- Mức: luồng nhẹ developer ép, Primary → check cấu trúc → Reviewer chỉ đọc; docs/config thuần túy.
- Môi trường: PowerShell/Python stdlib; script/log tạm backend/build/agent-workflow-lean ignored.

## Kết quả

Local: `py -3.14 -X utf8 backend/build/agent-workflow-lean/check.py` từ root (Python 3.14.6):
PASS 72 Markdown/231 link và anchor, 4 TOML đúng field, 33/33 ignore probes,
template trackable; bản M10.1 ignored/ngoài index, không đọc/hash. Diff-check PASS.
Script kiểm whitelist paths so main, frozen committed/staged không đổi; hai thay đổi ứng dụng local
ngoài scope được giữ ngoài index, không đọc/hash/sửa. Không tuyên bố checkout sạch.
Các file chính đạt giới hạn ký tự, bộ root+component+workflow giảm >=40% ở cả ba component;
template verification 26 dòng. Bảng kích thước và đối chiếu 56 nhóm quy tắc chỉ tại PR, không commit.
Rà nguồn đơn của quyền/ownership và câu task-specific: PASS; số từ khóa chỉ là hỗ trợ,
Reviewer phải kiểm nghĩa cả diff, không suy từ grep là tương đương.
Prune anchor chỉ trên đường dẫn được phép và không có inbound link Markdown; link checker xác nhận 0 lỗi.

Review: cùng một Reviewer chỉ đọc, vòng 1 so toàn diff main; R1 (tự gộp danh tính),
R2 (log nội dung CV/dữ liệu cá nhân) Medium và R3 (anchor không dùng) Low đã được Primary sửa.
Vòng 2 cuối review delta + finding cũ; kết quả/source cuối ghi tại PR.
Primary đã dừng mọi check/writer trước handoff; subagent không được cấp lease ghi.
CI đúng head sau push: link run/kết quả tại PR; không suy PASS từ local hoặc run cũ.
Application suite local N/A: chỉ docs/config, full gate dựa CI PR đúng head.
Native dispatch/full secret scan: **NOT VERIFIED**, chưa có event đúng custom-role/scanner đầy đủ;
cần phiên hỗ trợ role và scanner sẵn có được duyệt, không cài tool/đổi trust/máy.

## Open Questions

Khóa model/effort tùy chọn chưa áp dụng; nguồn đối chiếu ngày 2026-10-31:
[custom agents](https://learn.chatgpt.com/docs/agent-configuration/subagents),
[config](https://learn.chatgpt.com/docs/config-file/config-reference).
Schema có hai khóa agents.default_subagent_model/default_subagent_reasoning_effort và project overrides;
chưa có giá trị model độc lập tài khoản/effort phù hợp mọi mức nên giữ kế thừa, không đoán.
Không đổi ngôn ngữ, archive báo cáo cũ, gán Active hay thêm công cụ CI.
