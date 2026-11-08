# Kiểm chứng Mapping kinh nghiệm

- Spec/nguồn phê duyệt: [spec](spec.md) — chờ duyệt.
- Base/source SHA: `baf0eba` (main) khi lập kế hoạch.
- Mức workflow: E1–E3 mức 3, E4 mức 2 theo [workflow](../../AGENT_WORKFLOW.md).

## Check

| Acceptance criterion | Lệnh thực tế/thư mục | Source | Kết quả | Log/report tóm tắt |
| --- | --- | --- | --- | --- |
| Docs kế hoạch | `git diff --check`, link tương đối | PR docs | PASS | Chỉ docs, không đổi code |

Mỗi slice bổ sung dòng riêng (local và CI tách riêng).

## Open Questions

Xem [spec](spec.md#open-questions).
