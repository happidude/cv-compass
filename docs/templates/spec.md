<a id="specification"></a>

# Spec cho <task hoặc milestone>

<a id="approval"></a>

## Phê duyệt
- Nguồn phê duyệt, developer/ngày, phạm vi chính xác đã duyệt:
- Base commit, branch/worktree:
- Bằng chứng trạng thái (chưa xác nhận nếu chưa được xác lập rõ ràng):

<a id="goal-and-acceptance-criteria"></a>

## Mục tiêu và acceptance criteria
- Mục tiêu:
- Tiêu chí quan sát được, gồm trường hợp biên/lỗi:
- Ngoài phạm vi:

<a id="impact-and-ownership"></a>

## Ảnh hưởng và trách nhiệm
- File/module được phép:
- Trách nhiệm test theo [Thực hiện và bàn giao tại root](../../AGENTS.md#thực-hiện-và-bàn-giao):
- Người được phân công sửa, lease ghi độc quyền và handoff:
- Ảnh hưởng API/DB/security/dependency/quyền riêng tư/kiến trúc (không có hoặc quyết định đã duyệt):
- Quyết định còn mở và phần có thể làm độc lập:

<a id="verification-plan"></a>

## Kế hoạch kiểm chứng
- Acceptance/regression test thất bại trước khi đổi hành vi:
- Affected checks và full gate bắt buộc:
- Prerequisite, synthetic fixture, schema/storage cô lập:
- Check cho docs/config và lý do application suite là N/A nếu áp dụng:
- Report/warning dự kiến; tối đa ba vòng sửa–test–review:
- Quyền publish và giới hạn Draft/Ready của PR:
- Sửa CI/security-hook: spec đã duyệt liệt kê chính xác đường dẫn/mục đích, hoặc không có; không làm yếu/bỏ check:
- Xác nhận thao tác chỉ dành cho con người ở root bị loại khỏi phần agent thực hiện trong mọi task:
