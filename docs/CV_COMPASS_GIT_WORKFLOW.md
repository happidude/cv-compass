# Quy trình Git

Đọc [root](../AGENTS.md) về quyền/staging/ngôn ngữ, [workflow](AGENT_WORKFLOW.md) về gate.

## Branch và worktree

Trước làm: `git status --short`, `git branch --show-current`, `git worktree list`, base thống nhất;
báo đường dẫn/branch. Không triển khai trên main; không tự stash/reset/discard.
Milestone dùng branch/worktree riêng ngắn hạn sau duyệt; bảo trì được dùng lại checkout branch riêng
theo yêu cầu rõ. Nếu branch/path có sẵn, đọc ownership/base trước dùng.
Tạo khi cần: `git switch -c <branch>` hoặc `git worktree add -b <branch> <approved-path> <base>`.
Không đổi worktree người khác, repo lồng, batch cả kế hoạch tương lai hoặc switch/pull checkout khác
đang hoạt động. Fetch khi cần; milestone mới phải được giao và xác nhận prerequisite.

## Commit và PR

Branch: `feature/`, `fix/`, `chore/`, `refactor/`, `docs/` + mục đích thường-gạch-nối;
tên được yêu cầu ưu tiên. Conventional Commits, mệnh lệnh, mỗi commit một thay đổi logic;
tách format khỏi đổi hành vi. Đọc status/diff cuối; không stage cả thư mục bẩn/force-add artifact ignored.

```powershell
git diff --check
git add -- <explicit-approved-paths>
git diff --cached --check
git diff --cached
git commit -m "docs: streamline agent workflow"
git push -u origin <approved-branch>
```

PR nhắm main, dùng [template](../.github/pull_request_template.md); vẫn cần con người review.
Writer (implementer/tester) commit phần mình sửa, push feature branch sau mỗi commit; implementer mở Draft PR
ở lần push đầu (`gh pr create --draft`), các lần sau chỉ push. Chuyển Ready/merge chỉ con người.
