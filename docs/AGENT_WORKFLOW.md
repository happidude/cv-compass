# Workflow agent

Task rõ là phê duyệt; thiếu thì chặn phần phụ thuộc. Mục tiêu: đúng trước, rồi ít vòng và ít token.

## Nguyên tắc tiết kiệm

- Primary đọc spec/code liên quan **một lần** và chuyển tri thức qua brief. Subagent đã được nạp sẵn
  `CLAUDE.md`/`AGENTS.md`; không đọc lại toàn repo hay tài liệu ngoài brief.
- Feature lớn chia slice: mỗi PR một hành vi, mục tiêu ≤ khoảng 400 dòng code thay đổi (không tính test).
- Mỗi vòng chỉ chạy affected check; full gate một lần trên source cuối.
- Check do `verifier` chạy; log ghi file, chỉ trả tóm tắt. Log dài không vào hội thoại.
- Không review lại thay đổi chỉ format; delta review không đọc lại phần đã review.

## Chọn mức

| Mức | Điều kiện | Luồng | Review |
| --- | --- | --- | --- |
| 1 | Docs/format/comment/sửa hiển nhiên không đổi nghiệp vụ | Primary tự sửa → check cấu trúc | Không |
| 2 | Hành vi thường; quy tắc/AGENTS.md/policy/template/CI/security hook luôn tối thiểu mức 2 | Implementer + test → verifier affected → reviewer | 1 lượt + 1 delta |
| 3 | Persistence/migration, auth/security, logic dữ liệu cá nhân, API/OCR contract, transaction/async, cô lập/sandbox worker | Plan ngắn được chủ dự án duyệt → implementer + test → verifier full → reviewer | 1 lượt + 1 delta |

Developer được ép mức; nghi ngờ thì chọn cao hơn. Docs/config chỉ check cấu trúc, không test giả.
Tester riêng chỉ khi brief ghi lý do (ví dụ migration/contract cần kiểm chứng độc lập);
mặc định implementer viết test đi kèm, reviewer chỉ ra test còn thiếu.

## Brief (≤ 15 dòng)

```text
Mục tiêu/acceptance: <tiêu chí kiểm được>
Spec: <link, nguồn/ngày duyệt>
Mức/lý do: <1|2|3>
Path được sửa / loại trừ: <...>
Base: <SHA, branch/worktree>
Ngữ cảnh đã biết: <file/hàm/contract chính Primary đã đọc, để subagent không tìm lại>
Check affected: <lệnh cụ thể>
```

Primary chịu trách nhiệm bằng chứng; subagent không tự delegate/mở task mới.
Publish: writer commit + push feature branch; implementer mở Draft PR một lần, cập nhật mô tả khi gate xong.
Một writer tại một thời điểm: chờ writer/check dừng hẳn, đọc status/diff rồi mới giao tiếp.

## Review và vòng sửa

- Lượt đầu: toàn diff so với base + caller/contract/test bị ảnh hưởng; kiểm acceptance, tính đúng,
  ranh giới module, transaction, security, privacy/log, concurrency, test thiếu.
- Delta: chỉ thay đổi từ SHA đã review + finding còn mở.
- Finding: ID, severity, file:line, ảnh hưởng, bằng chứng, cách sửa. Không bịa lỗi từ check thiếu.
- **Chặn bàn giao:** Critical (lộ/mất dữ liệu), High (sai lớn về tính đúng/security/acceptance).
  **Medium:** sửa ngay nếu thuộc acceptance và sửa nhỏ; còn lại ghi `## Follow-up` trong PR.
  **Low:** ghi nhận.
- Sau 1 lượt + 1 delta vẫn còn Critical/High: BLOCKED, giữ bằng chứng, dừng và hỏi chủ dự án.
  Không tự thêm vòng, không tự bỏ finding.

## Gate bàn giao

Đạt acceptance, hết Critical/High, verifier PASS trên source cuối, review theo mức;
không secrets/CV/runtime artifact, `git diff --check` sạch. [Mẫu bằng chứng](templates/verification.md).
CI bắt buộc chưa xanh thì không nói sẵn sàng merge; CI bị chặn (billing/startup) ghi NOT VERIFIED và mở Draft.
[Git/PR](CV_COMPASS_GIT_WORKFLOW.md).

## Custom agent (Claude Code)

`CLAUDE.md` import `AGENTS.md` (nguồn quy tắc duy nhất); `backend|frontend|worker/CLAUDE.md`
import AGENTS component. Subagent ở `.claude/agents/`, gọi tuần tự:

| Agent | Model | Việc |
| --- | --- | --- |
| implementer | sonnet | Code + test trong path của brief |
| tester | sonnet | Chỉ khi brief yêu cầu; chỉ sửa test/fixture |
| verifier | haiku | Chạy check, trả tóm tắt PASS/FAIL; không sửa file |
| reviewer | inherit (model phiên chính) | Review chỉ đọc, trả finding |

`maxTurns` trong frontmatter là trần an toàn, chỉnh theo số liệu thực tế. Reviewer/verifier bị khóa
Write/Edit ở mức công cụ; Bash vẫn mở để đọc Git/chạy check nên phần chỉ đọc của Bash là chỉ dẫn.
Quyền dùng chung ở `.claude/settings.json`; thiết lập cá nhân ở `.claude/settings.local.json` (ignore).
`.codex/` giữ tạm để tương thích lịch sử, không còn là cấu hình chính; xóa khi chủ dự án duyệt.
