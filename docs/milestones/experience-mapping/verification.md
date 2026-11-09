# Kiểm chứng Mapping kinh nghiệm

- Spec/nguồn phê duyệt theo [spec](spec.md) và PR #33 đã merge.
- Base triển khai E1 là main tại 072dbea. E2–E4 nối tiếp branch của slice trước.
- Mức workflow: E1–E3 mức 3, E4 mức 2 theo [workflow](../../AGENT_WORKFLOW.md).

## Check

| Acceptance criterion | Lệnh thực tế/thư mục | Source | Kết quả | Log/report tóm tắt |
| --- | --- | --- | --- | --- |
| Docs kế hoạch | `git diff --check`, link tương đối | PR docs | PASS | Chỉ docs, không đổi code |
| E1 · Local affected backend và replay 3.0–3.3 | Spotless và Gradle ExperienceGrammar, PersonalInfoGrammar, MappingVersions, ExperienceEvaluation, MappingIntegration, PersonalInfoPublicationIntegration | eefdb1e | PASS | 123 test, 0 lỗi, 0 bỏ qua |
| E2 · Local affected backend và replay 3.0–3.3 | Cùng bộ kiểm chứng E1 | 49f1a4a | PASS | 128 test, 0 lỗi, 0 bỏ qua |
| E3 · Tổng tháng, contract hồ sơ và chỉnh sửa qua API | Spotless và Gradle ExperienceTotals, Candidate, M11Integration | Diff E3 trên 49f1a4a | PASS | 50 test trên JDK 25 và PostgreSQL 17 cô lập, 0 lỗi, 0 bỏ qua |

CI ghi riêng theo head của mỗi Draft PR. Full gate chạy trên E4.

## Open Questions

Xem [spec](spec.md#open-questions).

- Chủ dự án đã chốt giữ lựa chọn tính tổng khi ACCEPT đề xuất thay thế.
  Kỳ đã kết thúc được tính cả khi thiếu ngày nhập tham chiếu.
