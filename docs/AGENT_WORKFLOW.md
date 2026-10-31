# Workflow agent

Quyền/ownership/ngôn ngữ theo [root](../AGENTS.md); phê duyệt/base/phạm vi theo [spec](templates/spec.md).
Tin nhắn rõ là phê duyệt; thiếu thì chặn phần phụ thuộc.

## Chọn mức

| Mức | Điều kiện và luồng | Tối đa vòng |
| --- | --- | --- |
| 1 | Docs, format, comment, sửa hiển nhiên không đổi nghiệp vụ: Primary làm → check phù hợp → CI; không spawn subagent | 2 |
| 2 | Hành vi thông thường: Implementer (code + test) → affected tests → CI → Reviewer chỉ đọc; không Tester riêng | 2 |
| 3 | Bắt buộc: persistence/migration, auth/security, logic dữ liệu cá nhân, API/OCR contract, transaction/async, cô lập/sandbox worker hoặc > khoảng 10 file code: Implementer → Tester → Reviewer | 3 |

Brief theo root; developer được ép luồng.
Docs/config check cấu trúc, không thêm app test giả/dependency.

## Brief và handoff

Brief Primary dùng (dưới 25 dòng):

```text
Spec/phê duyệt: <link, nguồn/ngày>
Acceptance criteria: <tiêu chí cụ thể>
Đường dẫn được sửa/loại trừ: <danh sách>
Base SHA/source: <commit/fingerprint, branch/worktree>
Mức workflow/lý do/giới hạn vòng: <...>
Vai trò/chủ sửa/lease: <người, đường dẫn, lệnh, report>
Tài liệu cần đọc: <AGENTS root/component, spec/brief, tài liệu theo trigger>
không đọc tài liệu khác
Handoff: <file đã sửa/untracked, source, lệnh/kết quả, finding, người nhận>
```

Primary chịu trách nhiệm bằng chứng; subagent không tự giao việc tiếp/publish/task mới.
Trả lease trước writer kế tiếp:
chờ hoàn tất, xác nhận subprocess dừng, đọc status/diff; ngắt agent chưa đủ. Không ghi nền.
Worktree khác cần độc lập, không mặc nhiên sửa contract chung đồng thời.
Reviewer chờ writer/check dừng; không chạy formatter/build/test/công cụ tạo file, cài tool/đổi Git/publish.

## Vòng sửa và review

Nhịp affected/full gate và log theo [kiểm chứng](VERIFICATION.md).
Review đầu tính vòng 1; đổi agent/sửa lỗi không reset. Primary giao production defects → Implementer;
lỗi test do Tester thêm → Tester. Sửa rồi check và review lại trong ngân sách.

Review đầu: toàn diff/base (committed/staged/unstaged + file mới), caller/contract/test;
kiểm acceptance, tính đúng, module/transaction, security/privacy/concurrency/logging/test thiếu;
không liên quan ghi N/A có lý do. Lượt sau đọc delta từ SHA đã review + finding cũ;
chạm mức 3 thì cuối đọc lại toàn diff.
Finding: ID ổn định, severity, file:line, ảnh hưởng, bằng chứng/tái hiện, cách sửa/chủ sửa;
trạng thái mở/đã sửa và kiểm chứng/bác bỏ có bằng chứng. Thiếu check không phải lỗi bịa.
Critical (lộ/mất dữ liệu), High (lỗi lớn đúng/security/acceptance), Medium (lỗi/test thiếu đáng kể)
chặn nghiệm thu; Low có thể ghi nhận. Hết vòng còn blocker: BLOCKED, giữ bằng chứng,
cần task/quyết định mới có giới hạn; không tự thêm vòng/bỏ finding.

## Gate bàn giao

Đạt acceptance, hết blocker, local PASS, review theo mức;
không secrets/CV/runtime artifact, diff-check sạch. Sửa sau review phải review lại.
[Mẫu bằng chứng](templates/verification.md).
CI bắt buộc chưa xanh không tuyên bố sẵn sàng merge. Snapshot được yêu cầu rõ có thể publish Draft khi check bị chặn; ghi BLOCKED/NOT VERIFIED, chưa hoàn tất.
Dùng [Git](CV_COMPASS_GIT_WORKFLOW.md) và [PR template](../.github/pull_request_template.md).

## Custom agent

`.codex/agents/*.toml`: name/description/developer_instructions; một subagent, đóng xong mới tạo tiếp.
Model/effort kế thừa, không pin tài khoản.
Custom agent kế thừa quyền; Reviewer read-only là chỉ dẫn, không sandbox OS riêng.
Cần cô lập mạnh dùng phiên read-only; checkout có thể cần trust/phiên mới, không hot-reload.
Thiếu native dispatch: vai trò tuần tự có chỉ dẫn, báo rõ theo kiểm chứng.
Không cần global setting/credential/MCP/dependency mới.
