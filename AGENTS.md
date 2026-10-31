<a id="cv-compass--agent-rules"></a>

# CV Compass — Quy tắc cho agent

Áp dụng cho toàn repository; AGENTS.md của từng component bổ sung yêu cầu cục bộ.
Trước khi sửa, đọc [workflow](docs/AGENT_WORKFLOW.md), [policy](docs/AGENT_POLICY.md),
[quy trình Git](docs/CV_COMPASS_GIT_WORKFLOW.md), convention của component và spec
nhiệm vụ đã duyệt. [Mục lục tài liệu](docs/README.md) phân loại nguồn;
[roadmap](docs/roadmap.md) chỉ ghi phạm vi/trạng thái đã xác nhận.

<a id="scope-and-architecture"></a>

## Phạm vi và kiến trúc

- Chỉ làm nhiệm vụ đã duyệt. Không tự bắt đầu milestone khác.
  Phạm vi đã duyệt cho phép thực hiện; không hỏi lại cùng một quyền.
- Spring Boot sở hữu thao tác ghi DB, kiểm tra nghiệp vụ và chuẩn hóa. PDF child
  của Python luôn offline; chỉ host supervisor dùng internal HTTP API đã thống nhất.
- Giữ nguyên PDF gốc và raw extraction. Không bịa dữ liệu ứng viên,
  gộp danh tính theo tên hoặc gửi nội dung CV tới dịch vụ bên ngoài.
- Logic nghiệp vụ Java thuộc developer, trừ khi được cho phép rõ ràng.
  Công việc Python không cấp quyền sửa Java; sửa frontend phải nằm trong phạm vi rõ ràng.
- Không triển khai LLM/parser/prompt, search/ranking/embedding, hạ tầng mới hoặc
  tính năng tương lai nếu chưa được duyệt riêng. Code có sẵn không phải sự cho phép.

<a id="permissions-and-safety"></a>

## Quyền và an toàn

- Không đưa secrets và dữ liệu CV thật vào commit, log hoặc artifact công khai.
  Không truy cập môi trường riêng, cấu hình production hoặc CV thật trong phát triển thông thường.
- Chỉ được sửa CI workflow/security hooks khi spec đã duyệt liệt kê chính xác
  đường dẫn và thay đổi dự định. Không làm yếu assertion, bỏ check hoặc giảm coverage
  để che lỗi; đề xuất ngoài phạm vi ghi vào Open Questions.
- Merge, GitHub settings/branch protection/rulesets, tạo/sửa repository secrets,
  releases/tags và thao tác với database/server/deployment production chỉ do con người thực hiện.
  Agent không bao giờ thực hiện các thao tác này, bất kể task có cho phép hay không.
  Nhiệm vụ workflow này không thực hiện thao tác nào trong số đó.
- Không sửa Flyway migration đã áp dụng, force push, viết lại lịch sử, push main,
  bypass hooks, xóa remote branch hoặc dọn dẹp phá hủy dữ liệu. Không cài global tool
  hoặc thay đổi cấu hình máy.
- Schema/dependency mới, cấu hình build/formatter/security, API contract, logic dữ liệu cá nhân,
  ranh giới transaction/async, xóa file hoặc di chuyển file hàng loạt, shared module
  hoặc thay đổi kiến trúc cần phê duyệt phạm vi rõ ràng. Xem policy chi tiết.
- Ghi quyết định mới ngoài spec dưới `## Open Questions` trong PR; nêu bối cảnh,
  lựa chọn và khuyến nghị. Tiếp tục phần đã duyệt không phụ thuộc quyết định đó.
  Nếu quy tắc mâu thuẫn, làm theo quy tắc chặt hơn và ghi nhận mâu thuẫn.
- Giữ nguyên thay đổi của developer. Stage đường dẫn cụ thể. Track rules/docs dùng chung
  và cấu hình agent của dự án; tiếp tục ignore secrets, CV và dữ liệu runtime/build.

<a id="execution-and-delivery"></a>

## Thực hiện và bàn giao

- Primary điều phối spec đã duyệt → Implementer → Tester → Reviewer → PR.
  Mỗi working tree chỉ có một writer tại một thời điểm, kể cả Primary và các check
  tạo file. Reviewer chỉ đọc. Tuân theo quy trình handoff trong workflow.
- Implementer được viết test, helper và synthetic fixture đi cùng implementation;
  ưu tiên test-first khi đổi hành vi. Tester kiểm chứng độc lập theo acceptance criteria
  và bổ sung test còn thiếu, không bao giờ sửa production code. Primary phân công
  một writer cho từng lần sửa. Không vai trò nào được làm yếu assertion/check
  hoặc giảm coverage để che lỗi.
- Tối đa ba vòng sửa–test–review hoàn chỉnh; sau vòng 3 còn blocker thì báo BLOCKED.
  Không tự chấp nhận kết quả hoặc tự mở vòng 4.
- Dùng formatter đã cấu hình; không refactor ngoài phạm vi.
  Tài liệu, quy tắc/AGENTS, template và PR description viết bằng tiếng Việt; code, comment
  trong code, identifier và commit message viết bằng tiếng Anh. Comment giải thích lý do không hiển nhiên.
- Chạy local check phù hợp trên source cuối, đọc report mới và warning,
  rồi báo kết quả local/CI riêng. Check chưa thực hiện là NOT VERIFIED.
- Tạo Conventional Commit có phạm vi rõ, chỉ push feature branch đã duyệt và mở PR
  khi được phép; nhiệm vụ workflow này đã được cho phép.
  Tuân thủ giới hạn thao tác chỉ dành cho con người ở trên; không tự dọn branch/worktree.
