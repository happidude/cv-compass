<a id="agent-workflow"></a>

# Workflow agent

Dùng [quy tắc root](../AGENTS.md), [policy](AGENT_POLICY.md),
[quy trình Git](CV_COMPASS_GIT_WORKFLOW.md), rules/conventions của component và
[spec](templates/spec.md) đã duyệt. Quy trình này thay workflow một agent;
kết quả lịch sử không cấp quyền triển khai hoặc chứng minh nghiệm thu.

<a id="approval-and-orchestration"></a>

## Phê duyệt và điều phối

Primary ghi spec đã duyệt, nguồn/ngày phê duyệt, base commit, branch/worktree,
acceptance criteria, đường dẫn được phép, phần loại trừ và quyền.
Task rõ ràng trong tin nhắn có thể là nguồn phê duyệt; không cần duyệt spec lần hai
cho phạm vi đã được duyệt. Thiếu phê duyệt thì chặn phần triển khai phụ thuộc.
Không suy ra milestone Active/hoàn thành; xem [roadmap](roadmap.md).

Primary lần lượt điều phối spec → Implementer → Tester → Reviewer → PR.
Trách nhiệm test theo [Thực hiện và bàn giao tại root](../AGENTS.md#thực-hiện-và-bàn-giao).
Docs/config dùng check cấu trúc phù hợp; không thêm application test giả tạo
hoặc dependency. Ngôn ngữ theo [quy tắc root](../AGENTS.md#thực-hiện-và-bàn-giao).

| Vai trò | Phụ trách | Không được làm |
| --- | --- | --- |
| Primary | Spec, phạm vi, handoff, bằng chứng, vòng sửa có giới hạn, điều phối docs/config, bàn giao Git/PR | Ghi đồng thời hoặc tự chấp nhận blocker chưa giải quyết |
| Implementer | Production code đã duyệt, test/helper/synthetic fixture đi cùng và docs trong phạm vi | Làm yếu assertion/check/coverage hoặc mở rộng phạm vi |
| Tester | Kiểm chứng acceptance độc lập, test còn thiếu và test helper/synthetic fixture | Sửa production code, làm yếu assertion/check/coverage hoặc dùng subset thay full check |
| Reviewer | Review chỉ đọc toàn bộ diff/context và báo finding | Sửa file, chạy công cụ tạo file, nghiệm thu thay developer |

Mỗi working tree chỉ có một writer, kể cả Primary, agent, formatter/build và test
tạo file. Primary cấp lease ghi độc quyền rõ ràng: người giữ quyền, đường dẫn,
base/source, nhiệm vụ, lệnh được phép và report dự kiến. Chờ agent hoàn tất và trả
quyền trước writer kế tiếp; chỉ ngắt agent chưa đủ để trả quyền: xác nhận subprocess
đã dừng và đọc status/diff. Reviewer chỉ đọc khi mọi writer/check đã dừng.
Không ghi nền. Các worktree khác cần phạm vi độc lập; không mặc nhiên cho phép
sửa contract dùng chung đồng thời.

Mỗi handoff có link spec/phê duyệt, base/current commit hoặc fingerprint,
đường dẫn đã sửa/untracked, lệnh/kết quả, finding và người nhận tiếp theo.
Agent đọc quy tắc áp dụng; không được giao việc tiếp hoặc publish nếu Primary chưa
phân công rõ. Primary vẫn chịu trách nhiệm phạm vi và toàn bộ bằng chứng.

<a id="bounded-implementation-verification-and-review"></a>

## Triển khai, kiểm chứng và review có giới hạn

1. Chỉ triển khai tiêu chí đã duyệt, giữ ownership code và contract hiện có.
2. Tester chạy affected checks rồi full local gate áp dụng trên source cuối;
   theo [kiểm chứng](VERIFICATION.md), đọc report mới và warning.
3. Reviewer đọc toàn diff so với base thống nhất, phần staged/unstaged và file mới
   dự định đưa vào, cùng caller/contract/test liên quan. Đánh giá acceptance,
   tính đúng, ranh giới module/transaction, security/quyền riêng tư, concurrency,
   logging và thiếu test; mục không liên quan ghi N/A kèm lý do.
4. Finding cần ID ổn định, severity, file/line chính xác, ảnh hưởng, bằng chứng
   hoặc cách tái hiện, cách sửa đề xuất và trạng thái xử lý: mở / đã sửa và
   kiểm chứng / bác bỏ có bằng chứng. Thiếu check là thiếu bằng chứng, không phải lỗi bịa ra.
5. Primary giao lỗi production cho Implementer và lỗi trong test do Tester thêm
   cho Tester. Mỗi lần sửa có người phụ trách và lease ghi độc quyền rõ ràng,
   rồi chạy affected verification, full gate bắt buộc trên source cuối và Reviewer review lại.

Tối đa **ba vòng sửa–test–review hoàn chỉnh**, lần review đầu tính là vòng 1.
Giữ một bộ đếm cho task; sửa lỗi/đổi agent không đặt lại bộ đếm.
Critical: lộ dữ liệu/mất dữ liệu; High: lỗi lớn về tính đúng/security/acceptance;
Medium: lỗi hoặc thiếu test đáng kể; các mức đó chặn nghiệm thu. Low có thể ghi nhận.
Không tự mở vòng 4. Sau vòng 3 còn blocker: báo **BLOCKED**, giữ bằng chứng và yêu cầu
task/quyết định mới có giới hạn. Không bỏ finding để qua gate.
Vấn đề ngoài spec ghi trong Open Questions của PR; chỉ tiếp tục phần đã duyệt độc lập.
Trở ngại môi trường là BLOCKED, check là NOT VERIFIED; check thất bại được chứng minh
là FAIL. Cả hai đều không phải PASS.

<a id="delivery-gate"></a>

## Gate bàn giao

Đạt mọi tiêu chí, không còn Critical/High/Medium, local check trong phạm vi PASS,
review toàn diff cuối, không secrets/CV/runtime artifact và diff-check sạch.
Sửa sau review phải review lại trong ngân sách còn lại. Bằng chứng local và CI
xác định cùng source cuối; báo riêng từng loại, kể cả CI lọc theo đường dẫn.
Không tuyên bố sẵn sàng merge khi CI bắt buộc chưa xanh.

Dùng [mẫu kiểm chứng](templates/verification.md) và [PR template](../.github/pull_request_template.md).
Stage đường dẫn rõ ràng, đọc staged diff, commit theo Conventional Commits, push
branch đã duyệt và mở PR. Quyền commit/push đã có vẫn hiệu lực; không hỏi lại.
Nếu check bắt buộc bị chặn, source snapshot được yêu cầu rõ ràng có thể publish
dưới dạng **draft**, nêu blocker và check NOT VERIFIED; không coi là đã nghiệm thu/
hoàn tất. Sửa CI workflow/security hook cần spec đã duyệt liệt kê chính xác
đường dẫn và mục đích; không làm yếu hoặc bỏ check để có kết quả xanh.
Đề xuất ngoài phạm vi ghi dưới Open Questions. Merge, GitHub settings/branch
protection/rulesets, tạo/sửa repository secret, releases/tags và thao tác
database/server/deployment production chỉ dành cho con người: agent không bao giờ
thực hiện, bất kể task có cho phép hay không. Lượt follow-up workflow này không
thực hiện thao tác nào trong số đó. Không tự xóa branch/worktree.
Dừng ở nhiệm vụ được giao.

<a id="codex-custom-agents"></a>

## Custom agent của Codex

Đối chiếu tài liệu OpenAI chính thức ngày 2026-10-31:
[custom agent và kiểm soát](https://learn.chatgpt.com/docs/agent-configuration/subagents),
[tham chiếu cấu hình](https://learn.chatgpt.com/docs/config-file/config-reference),
và [cách nạp AGENTS.md](https://developers.openai.com/codex/guides/agents-md).

Định nghĩa dự án là các file riêng `.codex/agents/{implementer,tester,reviewer}.toml`;
field bắt buộc: name, description, developer_instructions.
`.codex/config.toml` bật agent và giới hạn một subagent đang mở; đóng agent đã
hoàn tất trước khi tạo agent khác. Primary vẫn kiểm soát lease ghi độc quyền.
Model/effort kế thừa phiên; không pin model riêng cho tài khoản.
Reviewer chỉ đọc theo chỉ dẫn: custom file kế thừa quyền của phiên chính và không
thể thực thi sandbox chỉ đọc riêng. Khi cần cô lập review mạnh hơn, dùng phiên chính
read-only; không khẳng định chỉ dẫn TOML thực thi cô lập ở mức hệ điều hành.

Cấu hình dự án có thể cần checkout được tin cậy và phiên mới. Xác minh riêng
syntax/schema và việc phát hiện role thực tế. Chat hiện tại không tự nạp lại role mới.
Nếu không có custom-role dispatch, báo rõ và dùng các vai trò được chỉ dẫn tuần tự,
không tuyên bố native dispatch thành công.
Không cần global setting, credential, MCP service hoặc dependency mới.
