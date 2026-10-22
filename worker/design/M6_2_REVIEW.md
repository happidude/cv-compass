> REVIEW LỊCH SỬ, không đại diện source PR9 hiện tại. Xem [PR9 revision](M6_PR9_REVISION.md) và [handoff](M6_BASELINE_HANDOFF.md).

# M6.2 — Developer/Reviewer record

Base: `5c06070955e2313f041be47ac313d97b2c079bea`, branch `feature/ocr-worker`,
worktree `E:/cv-compass-ocr-design`. Source chưa commit; code/data fingerprint:
`912d2a1de63b18610903df088e05001ad4884d399016f585e8f104bfdd0b89c6`
(`build/m6-2/final-source-manifest.json`,37 file code/schema/examples/config/locks/scripts;
Markdown báo cáo riêng, không nằm trong digest để tránh self-reference).

Đây là review cùng một agent đổi vai, không phải external audit. M6.1 review record
là lịch sử riêng; M6.2 tối đa3passes. Không commit/push/merge hoặc xóa worktree.

## Pass1 — phát hiện và sửa

Đã đọc actual status, committed diff against main (rỗng vì chưa commit), unstaged và
staged diffs (rỗng), đồng thời đọc toàn bộ intended new source/tests/config, contract
và tài liệu trực tiếp; Git diff thông thường không hiển thị untracked. Chỉ `worker/`
đổi, không thay backend/database/frontend. Source hiện tại là phiên bản được giao,
không tính các file lịch sử M6.1 là pipeline production đã được kiểm chứng.

| ID | Severity | Vị trí fix hiện tại | Vấn đề / tái hiện | Xử lý |
|---|---|---|---|---|
| M62-R1 | Medium | src/cv_compass_worker/pipeline.py:123 | Config giảm tổng text nhưng pipeline giữ mọi page tới publication, vượt budget trước khi nhận kết quả | Kiểm tra cumulative trước nhận page; giữ prefix nguyên vẹn, OUTPUT_LIMIT cho phần loại, không chạy page tiếp; regression test_document_text_cap_stops_before_next_engine |
| M62-R2 | Medium | src/cv_compass_worker/pipeline.py:139 | TEMP_LIMIT ởpage2 đểpage3 mang DOCUMENT_TIMEOUT dù chưa timeout | Remaining pages dùng đúng nguyên nhân fatal; regression test_fatal_temp_error_marks_remaining_with_actual_cause |
| M62-R3 | Medium | src/cv_compass_worker/pipeline.py:150 | Cleanup giữa pages có thể rơi vào document INTERNAL_ERROR | OSError cleanup chỉ CLEANUP_FAILED; cleanup cuối fail giữ lock; regression test_intermediate_cleanup_failure_does_not_fail_document và test_cleanup_failure_warns_keeps_success_and_halts_intake |

Các regression đã chạy Windows5/5 tại `build/m6-2/review-regressions-windows.log`.
Trong Developer validation cũng sửa: warning trùng khiến mixed-page schema reject;
placeholder total phải là số; đóng snapshot stream/Pillow view; raw_text không universal
newline conversion; timing trang lỗi giữ native elapsed và total thực; exact identifier
semantic validation chống newline cuối regex; malformed IPC thành ENGINE_CRASH và tiếp
trang khác. Không weaken assertion, suppress hay bỏ test lỗi.

## Pass2 — review bản sửa cuối

Đối chiếu lại toàn change, callbacks, child IPC, config/contract, tests và publication.
Các fix R1–R3 có regression tương ứng; final full checks hai OS ghi tại
M6_2_VERIFICATION.md. Không phát hiện thêm Critical/High/Medium trong phạm vi
**pipeline diagnostic với fixture thông thường**. Chưa cấp production readiness.

Kiểm tra kiến trúc/độ đúng/validation/errors/ownership/input snapshot/output limit,
transaction/concurrency: DB transaction và Modulith N/A vì không sửa Java/DB; sequential
children và workspace lock có test; crash recovery/exactly-once không được tuyên bố.
Không thêm job/HTTP/web/broker hoặc mapping field. Không sửa contract v1 đã duyệt;
semantic code siết exact full-string identifiers theo ý nghĩa contract hiện tại.

Blocker/verification gaps độc lập, chưa được đóng bởi technical PASS:

- GCC warning unresolved, binary production BLOCKED (upstream chưa xác nhận fix).
- Chưa hard isolation adapter đầy đủ cho hai OS; fail closed production.
- Chưa hostile PDF/resource-bomb/OOM/kill-parent/orphan recovery/hard-I/O deadline tests.
- Chưa corpus/manual ground truth/CER-WER/peak tree RAM/quality evaluation.
- CI trên source cuối NOT VERIFIED: chưa có commit/push được phê duyệt cho M6.2.

## Pass3 — rà soát kết thúc

Review lại thay đổi EOF/EOL/worker .gitattributes, bản report/JUnit/log thực tế cuối
và fingerprint sau rerun. Không thay logic sau pass2; code/data cuối khớp manifest37file.
Whitespace errors=0; Git EOL notice lịch sử requirements.txt được ghi riêng, không suppress.
R1–R3 fixed and verified trong final full checks51/0/0/0 mỗi OS. Không finding mới trong
scope diagnostic; production/quality gaps vẫn OPEN/NOT VERIFIED như bảng trên.
Dùng3/3reviewpasses. Không thêm sửa code hoặc review vòng4 trong invocation này.
