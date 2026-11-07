@AGENTS.md

# Ghi chú riêng cho Claude Code

Quy tắc chung nằm ở `AGENTS.md` (import ở trên) — đó là nguồn sự thật duy nhất; file này chỉ bổ sung
phần riêng của Claude Code, không lặp lại quy tắc.

- Subagent dự án: `.claude/agents/` (`implementer`, `tester`, `verifier`, `reviewer`). Primary là phiên chính;
  mức/handoff/số vòng theo [workflow](docs/AGENT_WORKFLOW.md). Chỉ một subagent ghi file tại một thời điểm.
- Tiết kiệm: Primary đọc một lần rồi giao brief; check giao `verifier`; không đưa log dài vào hội thoại.
- Quyền dùng chung: `.claude/settings.json` (track trong Git). Thiết lập cá nhân để ở
  `.claude/settings.local.json` (ignore). Deny-rule chỉ là lớp bảo vệ thêm, không thay quy tắc ở AGENTS.md.
- Model theo bảng trong workflow; phiên chính tự chọn model (mạnh cho plan/review mức 3).
- Trả lời người dùng bằng tiếng Việt, dễ hiểu; khi đề xuất giải pháp nêu ngắn gọn lý do chọn.
