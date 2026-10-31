# CV Compass — Quy tắc cho agent

Áp dụng toàn repo. Mặc định chỉ đọc file này, AGENTS component liên quan và spec đã duyệt.
Đọc [workflow](docs/AGENT_WORKFLOW.md) khi Primary chọn mức/handoff;
[policy](docs/AGENT_POLICY.md) khi xác định quyền hoặc sửa kiến trúc/dữ liệu ứng dụng;
[Git](docs/CV_COMPASS_GIT_WORKFLOW.md) khi thao tác branch/commit/PR;
[kiểm chứng](docs/VERIFICATION.md) khi chọn/chạy check. Convention/contract theo trigger component.

## Phạm vi

- Chỉ spec đã duyệt; không tự mở milestone, suy ra Active/hoàn thành hoặc coi code có sẵn là quyền.
  Đã duyệt thì làm, không hỏi lại. Java nghiệp vụ thuộc developer; Python không cấp quyền sửa Java;
  Java/frontend cần phạm vi rõ. Không refactor ngoài spec.
- Quyết định ngoài spec: `## Open Questions` trong PR, nêu bối cảnh/lựa chọn/khuyến nghị;
  dừng phần phụ thuộc, tiếp tục phần độc lập. Mâu thuẫn: theo quy tắc chặt hơn và ghi nhận.

## Quyền và an toàn

- Agent không bao giờ merge; đổi GitHub settings/branch protection/rulesets; tạo/sửa repository secrets;
  tạo release/tag; thao tác database/server/deployment production, bất kể task. Chỉ con người làm.
- Không xem/tiết lộ giá trị secret; không in/log/commit secrets hay CV thật hoặc đưa vào artifact công khai;
  không gửi nội dung CV tới dịch vụ bên ngoài. Phát triển thường không truy cập môi trường riêng,
  cấu hình production/CV thật hoặc gọi dịch vụ ứng dụng bằng credential thật.
- CI workflow/security hook chỉ sửa khi spec đã duyệt ghi chính xác đường dẫn và ý định.
  Không vai trò nào được làm yếu/xóa assertion, bỏ/skip check/test, giảm coverage hoặc blanket suppress để xanh.
- Không sửa Flyway đã áp dụng, force push, viết lại lịch sử, push main, bypass hook, xóa remote branch,
  dọn phá hủy filesystem/SQL/Git; không tự xóa branch/worktree. Không cài global tool, đổi cấu hình máy
  hoặc sửa ngoài repo. Sửa rules cần task cho phép.
- Giữ thay đổi developer; stage đường dẫn cụ thể. Track rules/docs/config agent dùng chung;
  ignore secrets/CV/runtime/build. Phạm vi cần duyệt thêm theo [policy](docs/AGENT_POLICY.md).

## Thực hiện và bàn giao

Primary ghi mức/lý do; nghi ngờ chọn cao hơn, developer được ép mức.

| Mức | Nhận diện ngắn |
| --- | --- |
| 1 | Docs/format/comment/sửa hiển nhiên không đổi nghiệp vụ |
| 2 | Hành vi thông thường |
| 3 | Persistence/security/privacy/contract/async/sandbox hoặc > khoảng 10 file code |

- Một writer mỗi working tree, kể cả Primary/check tạo file. Reviewer chỉ đọc; con người nghiệm thu.
- Implementer viết implementation + test/helper/synthetic fixture đi kèm, ưu tiên test-first khi đổi hành vi.
  Tester kiểm chứng độc lập acceptance, bổ sung test/helper/fixture thiếu, không bao giờ sửa production code.
  Primary giao chủ sửa; handoff/giới hạn vòng/gate theo workflow, không tự chấp nhận blocker.
- Dùng formatter hiện có. Tài liệu/AGENTS/template/PR description tiếng Việt;
  code/comment/identifier/commit message tiếng Anh; comment nêu lý do không hiển nhiên.
- Check/bằng chứng theo kiểm chứng; push feature branch/PR chỉ khi được phép;
  quyền đã có không hỏi lại. Dừng ở task được giao.
