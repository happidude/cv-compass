# Workflow agent

Task rõ là phê duyệt; thiếu thì chặn phần phụ thuộc.

## Chọn mức

| Mức | Điều kiện và luồng | Tối đa vòng |
| --- | --- | --- |
| 1 | Docs/format/comment/sửa hiển nhiên không đổi nghiệp vụ: Primary → check → CI; không spawn subagent | 2 |
| 2 | Hành vi thường; quy tắc/AGENTS.md/policy/TOML/template/CI/security hook luôn tối thiểu mức 2, kể cả docs: Implementer + test → affected tests → CI → Reviewer chỉ đọc; không Tester riêng | 2 |
| 3 | Bắt buộc: persistence/migration, auth/security, logic dữ liệu cá nhân, API/OCR contract, transaction/async, cô lập/sandbox worker hoặc > khoảng 10 file code: Implementer → Tester → Reviewer | 3 |

Developer được ép luồng. Docs/config chỉ check cấu trúc, không test giả/dependency.

## Brief và handoff

Brief (<25 dòng):

```text
Spec: <link, nguồn/ngày duyệt>; acceptance: <tiêu chí>
Path sửa/loại trừ: <...>
Base/source: <SHA/fingerprint, branch/worktree>
Mức/lý do/tối đa vòng: <...>
Role/chủ sửa/lease: <người, path, lệnh, report>
Tài liệu cần đọc: <AGENTS root/component, spec/brief; theo trigger>
Reviewer backend/worker: <thêm docs/AGENT_POLICY.md mục Kiến trúc và dữ liệu vào dòng trên>
không đọc tài liệu khác
Handoff: <file/untracked, source, lệnh/kết quả, finding, người nhận>
```

Primary chịu trách nhiệm bằng chứng; subagent không tự delegate/publish/task mới.
Trả lease trước writer kế: chờ xong, xác nhận subprocess dừng, đọc status/diff; interrupt chưa đủ.
Không ghi nền; worktree riêng cần độc lập, không mặc nhiên sửa contract chung đồng thời.
Reviewer chờ writer/check dừng; không format/build/test/tool ghi, cài tool/đổi Git/publish.

## Vòng sửa và review

Check/log: [kiểm chứng](VERIFICATION.md). Review đầu là vòng 1; đổi agent/sửa lỗi không reset.
Primary giao lỗi production → Implementer, lỗi test do Tester thêm → Tester; sửa/check/review trong ngân sách.
Review đầu: toàn diff/base (committed/staged/unstaged/mới), caller/contract/test;
kiểm acceptance/đúng/module/transaction/security/privacy/concurrency/logging/test thiếu; N/A cần lý do.
Sau: delta từ SHA đã review + finding cũ; mức 3 cuối đọc toàn diff.
Finding: ID ổn định, severity, file:line, ảnh hưởng, bằng chứng/tái hiện, cách sửa/chủ sửa;
mở/đã sửa và kiểm chứng/bác bỏ có bằng chứng. Không bịa lỗi từ check thiếu.
Critical (lộ/mất dữ liệu), High (lỗi lớn đúng/security/acceptance), Medium (lỗi/test thiếu đáng kể) chặn nghiệm thu;
Low có thể ghi nhận. Hết vòng còn blocker: BLOCKED, giữ bằng chứng, cần task/quyết định mới có giới hạn;
không tự thêm vòng/bỏ finding.

## Gate bàn giao

Đạt acceptance, hết blocker, local PASS, review theo mức; không secrets/CV/runtime artifact, diff-check sạch.
Sửa sau review phải review lại; [mẫu bằng chứng](templates/verification.md).
CI bắt buộc chưa xanh không nói sẵn sàng merge. Snapshot yêu cầu rõ có thể Draft khi check bị chặn;
ghi BLOCKED/NOT VERIFIED, chưa hoàn tất. [Git/PR](CV_COMPASS_GIT_WORKFLOW.md).

## Custom agent

Agent chính là **Claude Code**. `CLAUDE.md` import `AGENTS.md` (nguồn quy tắc duy nhất);
component `backend|frontend|worker/CLAUDE.md` import AGENTS component tương ứng.
Subagent: `.claude/agents/{implementer,tester,reviewer}.md` (frontmatter `name`/`description`/`tools`);
gọi tuần tự, chỉ một subagent ghi tại một thời điểm, đóng xong mới gọi tiếp.
Model `inherit`; không pin model/effort tài khoản trong file dự án.
Reviewer bị khóa Write/Edit ở mức công cụ; Bash vẫn mở để đọc Git nên read-only của Bash là chỉ dẫn.
Quyền dùng chung ở `.claude/settings.json`; thiết lập cá nhân ở `.claude/settings.local.json` (ignore).
Thiếu dispatch subagent: chạy vai trò tuần tự trong phiên chính và báo rõ.
`.codex/` giữ tạm để tương thích lịch sử, không còn là cấu hình chính; xóa khi chủ dự án duyệt.
Không cần global setting/credential/MCP/dependency mới.
