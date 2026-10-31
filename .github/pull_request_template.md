<a id="summary"></a>

## Tóm tắt
<Vấn đề, hành vi hoặc kết quả tài liệu sau thay đổi và quyết định quan trọng.>

<a id="approved-spec-and-changes"></a>

## Spec đã duyệt và thay đổi
- Spec/nguồn phê duyệt:
- Acceptance criteria và bằng chứng:
- File/module thay đổi:
- Ảnh hưởng API/DB/dependency/security/quyền riêng tư:
- Trách nhiệm test và handoff theo [Thực hiện và bàn giao tại root](../AGENTS.md#thực-hiện-và-bàn-giao):
- Phạm vi CI/security-hook: spec đã duyệt với chính xác đường dẫn/mục đích, hoặc không có:
- Thao tác chỉ dành cho con người: xác nhận agent không merge, đổi GitHub settings/branch protection/rulesets, tạo/sửa repository secret, releases/tags hoặc thao tác database/server/deployment production:
- Phần ngoài phạm vi:

<a id="verification"></a>

## Kiểm chứng
- Commit hoặc fingerprint payload đã review chính xác:
- Lệnh local, môi trường, số liệu mới, warning và kết quả:
- Kết quả CI và head mới chính xác, tách khỏi local và bằng chứng bàn giao trước:
- Native role dispatch và secret scan: lệnh/event thực tế, kết quả hoặc NOT VERIFIED/bước tiếp theo:
- PR template chính đã track; bản nháp riêng tư ignored/không có trong commit; diff application/dependency/build/formatter/CI đóng băng:
- Reviewer/số vòng (tối đa ba), finding và cách xử lý:
- Check NOT VERIFIED / N/A, lý do và bước tiếp theo:
- Gate: sẵn sàng review / bản nháp BLOCKED (không tự chấp nhận blocker).

## Open Questions
<Không có, hoặc bối cảnh/lựa chọn/khuyến nghị và phần chưa thực hiện cho từng quyết định ngoài spec.>

<a id="limitations-and-next-step"></a>

## Giới hạn và bước tiếp theo
<Giới hạn còn lại và bước tiếp theo. Agent không bao giờ thực hiện thao tác chỉ
dành cho con người ở root, bất kể task có cho phép hay không. Ghi phần loại trừ
thực tế của task và giữ mọi chỉ dẫn chỉ dùng Draft.>
