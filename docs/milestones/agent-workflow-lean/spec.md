# Spec workflow agent gọn

## Phê duyệt và source

Developer giao trong chat ngày 2026-10-31; đã xác minh PR #20 merge tại
`7feddfbc737f0946fa815d9b1d5c03ecfd1184b9`, tạo `chore/agent-workflow-lean` từ main đúng SHA đó.
Checkout `E:/cv-compass`; giữ các thay đổi ứng dụng local ngoài phạm vi, không đọc/stage.

Phạm vi: AGENTS root/component, docs/AGENT_WORKFLOW.md, AGENT_POLICY.md, VERIFICATION.md,
CV_COMPASS_GIT_WORKFLOW.md, README.md trong docs, docs/templates/*, .github/pull_request_template.md,
.codex/agents/*.toml, .codex/config.toml, docs/milestones/*. Không sửa application/dependency/
build/formatter/CI/canonical/history, không merge/settings/Ready. Cho phép commit/push branch này và Draft PR.

## Acceptance

- Phân mức rủi ro, đọc theo trigger và brief <= khoảng 25 dòng; giữ mọi ràng buộc an toàn,
  ownership, lease, evidence; bỏ trùng/câu riêng task/anchor không được link tới trong file được phép.
- Root <= 3.500, workflow <= 4.000, policy <= 3.000, Git <= 1.500, verification <= 3.000 ký tự;
  root + mỗi component + workflow giảm >= 40%; verification template <= 40 dòng.
- Rút gọn spec/verification PR #20, nguồn đầy đủ qua link commit bất biến; không tạo bảng bằng chứng lớn.
- Check toàn Markdown link/anchor, TOML đúng field, ignore, diff-check, frozen diff so main;
  bảng ràng buộc cũ→mới <= khoảng 60 dòng và kích thước trước/sau chỉ trong PR description.
- Reviewer so toàn diff main, nhấn mạnh cấm/bắt buộc/giới hạn không bị nhẹ đi.
  Local/CI riêng, chỉ PASS CI đúng head đã kết thúc; native/secret scan không có bằng chứng giữ NOT VERIFIED.

## Mức và kế hoạch

Developer ép luồng nhẹ cho task docs/config: Primary sửa/check cấu trúc → một Reviewer chỉ đọc;
ngoại lệ có chủ ý so với mức 1 thông thường không spawn. Tối đa 2 vòng sửa–review, không Tester riêng.
Primary là writer duy nhất, dừng check trước review. Check/script/log tạm trong backend/build ignored.
Model/effort kế thừa; đối chiếu official config trước mọi khóa tùy chọn, không pin model tài khoản.

## Open Questions

Không đổi ngôn ngữ, archive M6/M10/M11, gán Active roadmap hoặc thêm ArchUnit/pytest/gitleaks CI.
