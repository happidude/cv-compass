<a id="git-branch-and-worktree-procedure"></a>

# Quy trình branch và worktree Git

Tuân theo [quy tắc root](../AGENTS.md) và [workflow agent](AGENT_WORKFLOW.md).
Phạm vi/trạng thái milestone thuộc [roadmap](roadmap.md) và spec đã duyệt,
không đặt trong quy trình này. [Kế hoạch cũ](history/legacy-milestone-plan.md) là lịch sử.

<a id="preflight"></a>

## Kiểm tra trước khi làm

Đọc thư mục hiện tại, branch, status, worktree và base thống nhất. Giữ thay đổi
của developer; không tự stash/reset/discard. Không triển khai trên main.
Mỗi milestone dùng branch/worktree riêng tồn tại ngắn; chỉ tạo khi cần và
sau khi duyệt phạm vi. Task bảo trì có thể dùng lại checkout sạch trên branch riêng
được yêu cầu rõ ràng, như task workflow này.

PowerShell; trên Linux dùng cùng lệnh Git với đường dẫn repository tương ứng:

```powershell
git status --short
git branch --show-current
git worktree list
git branch --list chore/example
# Create only if the requested branch does not exist and checkout is clean:
git switch -c chore/example
```

Tạo worktree milestone mới bằng `git worktree add -b feature/example <approved-path> <agreed-base>`.
Nếu branch/đường dẫn đã tồn tại, đọc ownership và base trước khi dùng lại.
Không thay đổi worktree của người khác, tạo repository lồng nhau hoặc chạy toàn
kế hoạch tương lai như một batch lệnh. Fetch khi cần; không switch/pull checkout khác
đang hoạt động hoặc viết lại lịch sử đã publish. Báo đường dẫn/branch trước triển khai.

<a id="names-commits-and-publishing"></a>

## Tên, commit và publish

Branch: `feature/`, `fix/`, `chore/`, `refactor/`, `docs/` kèm mục đích viết thường,
ngăn bằng dấu gạch nối. Tên được yêu cầu rõ ràng được ưu tiên.
Dùng Conventional Commits, câu mệnh lệnh và một thay đổi logic mỗi commit;
ngôn ngữ theo [quy tắc root](../AGENTS.md#thực-hiện-và-bàn-giao).
Tách commit chỉ format khỏi thay đổi hành vi.

Chạy check/formatter áp dụng và review có giới hạn, đọc status/diff cuối,
rồi stage đường dẫn cụ thể đã duyệt. Không stage cả thư mục đang bẩn.
Không force-add secrets/runtime artifact bị ignore; rules/docs dùng chung phải
được track thông qua quy tắc ignore đã review.

```powershell
git diff --check
git add -- <explicit-approved-paths>
git diff --cached --stat
git diff --cached --check
git diff --cached
git commit -m "chore: apply approved agent workflow"
git push -u origin <approved-branch>
```

Khi task đã cho phép commit/push/mở PR thì thực hiện, không hỏi lại.
Nếu chưa, chuẩn bị kết quả cụ thể đã review trước. Không push check đang fail
dưới dạng sẵn sàng: source snapshot được cho phép rõ ràng có thể dùng Draft PR
với blocker/NOT VERIFIED cụ thể theo workflow agent.

PR nhắm main và dùng [PR template](../.github/pull_request_template.md), spec,
kiểm chứng, source chính xác, finding/số vòng review và Open Questions.
Vẫn bắt buộc con người review. Merge, GitHub settings/branch protection/rulesets,
tạo/sửa repository secret, releases/tags và thao tác database/server/deployment
production chỉ dành cho con người. Agent không bao giờ thực hiện,
bất kể task có cho phép hay không.
Không force push, push main, viết lại lịch sử, bypass hook hoặc xóa remote branch.
Không tự xóa local branch/worktree.

<a id="coordination"></a>

## Phối hợp

Mỗi branch/worktree chỉ có một writer tại một thời điểm, kể cả công cụ kiểm chứng.
Ghi handoff ownership rõ ràng trong spec/verification/PR, không chỉ dựa vào trí nhớ chat.
Worktree riêng không cấp quyền sửa contract dùng chung đồng thời.
Không bắt đầu milestone khác khi chưa được giao rõ ràng và xác nhận prerequisite.
