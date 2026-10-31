<a id="agent-workflow-maintenance-specification"></a>

# Spec bảo trì workflow agent

<a id="approval"></a>

## Phê duyệt

Yêu cầu developer trong chat ngày 2026-10-31 duyệt task workflow này, sửa quy tắc/
cấu hình agent, branch `chore/agent-workflow`, commit/push và PR. File này ghi lại
quyền đã có; không cho phép milestone ứng dụng mới.
Base: `81a8a62`; dùng lại checkout sạch `E:/cv-compass` trên branch bảo trì riêng
được yêu cầu. Task docs/config này không cần worktree milestone bổ sung.
Giữ nguyên các worktree khác.

<a id="goal-and-acceptance"></a>

## Mục tiêu và nghiệm thu

1. Đọc source, docs và CI hiện có; giữ application code/dependency/CI.
2. Phân loại quy tắc/convention/contract/vận hành/spec/kiểm chứng/lịch sử.
   Chỉ archive phần đã mất hiệu lực sau kiểm tra tham chiếu; tách file trộn nội dung.
3. Quy tắc root ngắn cùng quy tắc backend/worker/frontend; giữ convention chi tiết.
4. Thêm workflow, roadmap, danh mục milestone và mẫu spec/verification/PR;
   không suy ra Active hoặc trạng thái hoàn thành của ứng dụng.
5. Cấu hình Implementer, Tester và Reviewer chỉ đọc theo tài liệu Codex chính thức
   đã xác minh. Primary điều phối; mỗi lúc một writer.
6. Thực hiện phạm vi đã duyệt mà không hỏi lại; báo thay đổi phạm vi,
   tối đa ba vòng và BLOCKED nếu còn lỗi.
7. Track docs/rules/config dùng chung, giữ secrets/CV/runtime data ignored.
8. Kiểm tra link/config/ignore/phần đóng băng, commit, push và mở PR;
   không merge/deploy hoặc đổi GitHub settings.

<a id="impact-and-verification-plan"></a>

## Ảnh hưởng và kế hoạch kiểm chứng

Được phép: quy tắc root/component, tài liệu/link/phân loại dùng chung, TOML agent
dự án và quy tắc ignore root. Không đổi API/DB/security/application dependency
hoặc production. Giữ đường dẫn/byte của runtime contract artifact hiện có.
Primary viết docs/config; Tester kiểm chứng rồi Reviewer đọc tuần tự.
Check cấu trúc docs/config thay application test không liên quan; không thêm
dependency công cụ. Nghiệm thu source không chứng nhận production OCR, accuracy
hoặc milestone ứng dụng.

Kiểm tra relative target/anchor, shape TOML, ranh giới vai trò, chuyển tham chiếu,
ignore probe, CI/source/build/dependency không đổi và whitespace diff.
Ghi native role discovery hoặc CI chưa có là NOT VERIFIED. Tối đa ba vòng review.
Publish Draft nếu check bắt buộc còn bị chặn.

<a id="follow-up-authorization--2026-10-31"></a>

## Phê duyệt follow-up — 2026-10-31

Bản ghi phê duyệt lịch sử cho fcd986783e2176a98af4b51a995212c5e402a181.
Policy thao tác nhạy cảm của lượt đó đã bị lượt sửa có giới hạn thứ hai bên dưới
thay thế; không cấp quyền hiện tại cho thao tác chỉ dành cho con người.

Yêu cầu developer rõ ràng mới duyệt ngân sách tối đa ba vòng sửa–test–review mới
cho Draft PR #20 trên chore/agent-workflow. Head local/PR đầu lượt đã đọc:
f0c4049a28da23f79ffcd6c965b048dcba3cc8e3; base PR:
81a8a6220e34871ea9fc68aa8e65d19785d0eff9. Check/review trước vẫn là bằng chứng
lịch sử cho lần bàn giao đầu, không kiểm chứng follow-up này.

Acceptance criteria:
1. Trách nhiệm test theo [Thực hiện và bàn giao tại root](../../../AGENTS.md#thực-hiện-và-bàn-giao); lượt trước kiểm tra nguồn đó và handoff trong phạm vi.
2. Lượt trước sửa phạm vi CI/security hook và cách diễn đạt thao tác nhạy cảm.
   Policy sau đã bị quy tắc chỉ dành cho con người của lượt sửa có giới hạn
   thứ hai thay thế; tiêu chí lịch sử này không cấp quyền hiện tại.
3. PR template chính là .github/pull_request_template.md, được track;
   mẫu docs cũ chỉ còn điểm trỏ và cập nhật link liên quan.
4. Đồng bộ rules/policy/workflow/verification/template/custom-agent đang có hiệu lực.
   Không viết lại bằng chứng lịch sử để ngụ ý đã kiểm chứng mới.
5. Kiểm tra độc lập link/anchor, TOML, ignore, whitespace staged/unstaged,
   template được track và bản nháp riêng tư bị loại; review toàn diff PR.
   Chứng minh application/dependency/build/formatter/CI không đổi so với base PR.
6. Thử native role dispatch và secret scanner sẵn có, không cài global hoặc đổi
   cấu hình máy; không hỗ trợ thì NOT VERIFIED kèm bước tiếp theo.
7. Conventional Commit, push thường cùng branch, cập nhật PR description với
   head cuối và bằng chứng thực tế. Giữ Draft; không merge/force push/Ready/settings.

Đường dẫn được phép: AGENTS root/component, TOML agent .codex, policy/workflow/
verification, template/docs/link liên quan và .github/pull_request_template.md.
Application, dependency, build/formatter config và .github/workflows đóng băng.
Không archive mới, quét gom docs trùng lặp hoặc gán milestone Active.
Primary điều phối → Implementer sửa → Tester kiểm chứng → Reviewer chỉ đọc,
handoff độc quyền.

<a id="second-bounded-revision-authorization--2026-10-31"></a>

## Phê duyệt lượt sửa có giới hạn thứ hai — 2026-10-31

Developer duyệt lượt mới từ head local/PR đã đọc
fcd986783e2176a98af4b51a995212c5e402a181; base PR vẫn là
81a8a6220e34871ea9fc68aa8e65d19785d0eff9. Dùng branch và Draft PR #20 hiện có.
Bộ đếm mới bắt đầu vòng 1, tổng tối đa ba vòng sửa–test–review,
kể cả pha dịch được review riêng. Không tạo branch hoặc PR mới.

Nghiệm thu pha 1, commit tiếng Anh trước:
- Khôi phục merge, GitHub settings/branch protection/rulesets, tạo/sửa repository
  secret, releases/tags và thao tác DB/server/deployment production chỉ dành cho
  con người, agent không bao giờ thực hiện bất kể task. CI/security hook chỉ được
  sửa khi spec đã duyệt liệt kê chính xác đường dẫn và mục đích; không làm yếu check.
  Giữ bí mật secrets/CV và không gửi nội dung CV ra bên ngoài.
- Toàn bộ diễn giải trách nhiệm test chỉ ở root AGENTS.md; xóa phần thêm tại
  component, policy/verification/PR template trỏ về root. Workflow giữ bảng vai trò,
  giao lỗi production cho Implementer và lỗi test do Tester thêm cho Tester.
  TOML agent dùng một câu ownership cùng ràng buộc riêng của vai trò.
- Kiểm tra cách diễn đạt đang hiệu lực toàn repo ngoài docs/history, không đọc
  bản nháp M10.1 riêng tư. Spec/bằng chứng cũ là bản ghi phê duyệt lịch sử đã bị
  thay thế, không cấp quyền hiện tại. Đánh dấu rõ cách diễn đạt lịch sử còn giữ.
- Tester kiểm chứng và Reviewer đọc toàn diff PR trước commit 1, message
  docs: ..., bằng tiếng Anh. Không dịch trước checkpoint này.

Nghiệm thu pha 2, commit bản dịch riêng:
- Dịch Markdown tiếng Anh được PR thêm/sửa và description/instructions agent
  sang tiếng Việt, không đổi yêu cầu. Loại docs/history/*,
  backend/HISTORICAL_VERIFICATION.md, bản nháp database POC, file đã tiếng Việt
  và toàn bộ artifact application/canonical JSON/CI đóng băng; giữ nguyên byte file loại trừ.
- Giữ đường dẫn, URL, lệnh/flag, code block, key/name TOML, tên vai trò,
  nhãn trạng thái quy ước và thuật ngữ kỹ thuật. Cập nhật mọi inbound heading anchor.
- Chỉ root AGENTS.md định nghĩa ngôn ngữ: docs/rules/templates/PR tiếng Việt;
  code/comment trong code/identifier/commit message tiếng Anh. File khác trỏ về root.
- Tester lập bảng tương đương từng quy tắc bắt buộc so với commit 1, kiểm tra
  tiếng Anh còn lại với ngoại lệ rõ ràng và chạy lại link/TOML/ignore/whitespace/
  diff đóng băng. Reviewer chỉ đọc so sánh hai bản.
- Message commit 2: docs: translate shared agent documentation to Vietnamese.
  PR body tiếng Việt liệt kê hai SHA, file đã dịch/loại trừ và lý do.

Cả hai pha: một writer; Primary → Implementer → Tester → Reviewer chỉ đọc;
không có relative Markdown target/anchor hỏng, TOML parse được, ignore probe
bảo vệ M10.1 riêng tư và PR template chính vẫn được track. Application/source,
dependency/build/formatter và CI workflow đóng băng so với base81a8a62.
Native dispatch/full secret scan vẫn NOT VERIFIED trừ khi thực tế được chứng minh.
Chỉ push thường lên chore/agent-workflow; giữ Draft, không Ready/merge/force push
hoặc thao tác nhạy cảm. Kết quả CI phải xác định head mới; không báo CI PASS sớm.

<a id="open-questions--deferred-work"></a>

## Open Questions — công việc hoãn lại

Chưa được phép: bỏ phát biểu riêng của task khỏi quy tắc/template lâu dài;
gộp ba tài liệu workflow; archive report M6/M10/M11; gán Active trong roadmap.
Ghi là công việc tiếp theo trong PR; không thực hiện trong lượt này.
