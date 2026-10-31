<a id="local-verification-procedure"></a>

# Quy trình kiểm chứng local

Áp dụng cho source cuối, cùng README/convention component và spec đã duyệt.
Bằng chứng lịch sử được phân loại trong [mục lục docs](README.md).
Dùng [mẫu bằng chứng](templates/verification.md) và
[PR template chính](../.github/pull_request_template.md).
Trách nhiệm test theo [Thực hiện và bàn giao tại root](../AGENTS.md#thực-hiện-và-bàn-giao).

<a id="prepare-and-execute"></a>

## Chuẩn bị và thực hiện

Đọc thư mục, branch, status và prerequisite. Giữ thay đổi của developer.
Kiểm tra biến test có được đặt mà không in giá trị; không đọc private env file
hoặc trỏ test sang dữ liệu development/production. Dùng schema, storage và
resource tổng hợp cô lập. Thiếu prerequisite: BLOCKED / NOT VERIFIED kèm bước tiếp theo.

| Phạm vi ảnh hưởng | Local check bắt buộc |
| --- | --- |
| Backend code/tests | Affected tests, formatter đã cấu hình, rồi full `./gradlew check --rerun-tasks` từ backend (PowerShell `.\gradlew.bat check --rerun-tasks`), Java 25/PostgreSQL 17; Modulith khi áp dụng |
| Worker code/tests | Đường dẫn Ruff format/check trong worker rules; Ubuntu/WSL CPython 3.14.8 đã provision, Docker Linux/cgroup v2, baseline builder hiện có rồi verifier; bằng chứng full `tests/run_checks.py --xml <fresh-path>` mới trong verifier |
| Frontend code/tests | Prettier hiện có, `npm run check` và `npm run test:browser` từ frontend |
| Chỉ docs/rules/agent TOML | Link/anchor tương đối, audit phân loại/tham chiếu, field TOML và ranh giới vai trò, kiểm tra Git ignore/staging, diff CI/application đóng băng, PR template chính đã track, loại bản nháp riêng tư, `git diff --check` và `git diff --cached --check`; application suite N/A kèm lý do |

Full gate worker, chạy từ root trên Linux host đã provision:
`python3.14 worker/spikes/m6_2_hardening/build_baseline.py` rồi
`python3.14 worker/spikes/m6_2_hardening/verify_baseline.py`. Dùng output mới theo
README; chạy `--cleanup-only` theo ownership tương ứng kể cả thất bại, chỉ sau
khi lượt chạy đã dừng. Không gọi subset là full Docker verification.
Chạy công cụ hiện có, không cài global tool hoặc đổi application dependency.

<a id="inspect-evidence-and-warnings"></a>

## Đọc bằng chứng và warning

Đọc terminal log và JUnit/XML/HTML/problems report mới khi được tạo.
Ghi số tests/failures/errors/skips, task đã chạy và report dự kiến còn thiếu.
UP-TO-DATE, NO-SOURCE, cache và report cũ không chứng minh đã thực thi;
N/A cần lý do cụ thể. Đánh giá các dòng WARN/ERROR dự kiến từ negative test.

Giữ `-Xlint:deprecation` và gate formatter/linter hiện có. Sửa warning mới
trong phạm vi; ghi nguồn/nguyên nhân/ảnh hưởng và lý do hoãn cho warning ngoài
phạm vi hoặc từ công cụ. Không đổi build config, suppress warning hoặc bỏ
assertion để che lỗi. Kiểm tra lại source cuối sau sửa, trong ngân sách ba vòng.

Ghi commit source chính xác hoặc base kèm fingerprint file trước commit;
commit chỉ cập nhật bằng chứng tài liệu có thể tham chiếu payload đã review giống hệt.
Không chèn hash commit tương lai vào chính nó. Source CI và local phải nhận diện
riêng được. PASS là đã chạy thành công; FAIL là thất bại được chứng minh;
NOT VERIFIED là thiếu bằng chứng; N/A là không liên quan kèm lý do.

<a id="existing-ci-and-task-scope"></a>

## CI hiện có và phạm vi task

- Persistence CI: mọi PR/push tới main; Java 25/PostgreSQL 17, Gradle check.
- Worker CI: lọc đường dẫn worker/workflow; build baseline Python/Docker đã
  provision, full verifier, cleanup theo ownership và artifact provenance.
- Frontend CI: lọc đường dẫn frontend/workflow; Node 24, cài từ lockfile,
  format/lint/typecheck/unit/build và Chromium browser check.

Chỉ được sửa CI workflow/security hook khi spec đã duyệt liệt kê chính xác
đường dẫn và mục đích; không làm yếu/bỏ check để che thất bại.
Đề xuất ngoài phạm vi thuộc Open Questions. Lượt follow-up docs/config này giữ
nguyên CI pipeline, unittest và không thêm công cụ/dependency. Rule file dưới
component có thể kích hoạt CI component; chỉ docs/root có thể không kích hoạt
job lọc đường dẫn. Run thiếu/pending không phải PASS; kiểm tra chính xác head mới
trước khi báo CI PASS. Không mặc nhiên cho rằng đã tự động secret/dependency scan.

Thử native custom-agent dispatch và secret scanner sẵn có khi môi trường hỗ trợ.
Ghi lệnh/event/bằng chứng thực tế và source; TOML/parser PASS không chứng minh
role dispatch. Không cài global tool hoặc đổi cấu hình máy/trust để check chạy được.
Nếu không có, ghi NOT VERIFIED, giới hạn cụ thể và bước tiếp theo. Giữ bằng chứng
follow-up mới riêng với các check/ngân sách review của lần bàn giao lịch sử.

Agent không bao giờ thực hiện các thao tác chỉ dành cho con người tại
[quy tắc root](../AGENTS.md#quyền-và-an-toàn) và
[policy Level A](AGENT_POLICY.md#level-a--thao-tác-chỉ-dành-cho-con-người-và-nguyên-tắc-an-toàn),
bất kể task có cho phép hay không.
