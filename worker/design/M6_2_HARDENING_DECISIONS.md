# M6.2 hardening — quyết định trước triển khai (2026-10-18)

## Điều chỉnh nghiệm thu được developer duyệt — 2026-10-21

M6 baseline là worker offline PDF → text theo trang với pipeline hiện có, giữ toàn
bộ yêu cầu contract, timeout, resource isolation, cleanup, source integrity, output
limits và xử lý lỗi. Không business mapping, candidate parsing hoặc DB writes.
Corpus, ground truth, CER/WER và quality tuning chưa thuộc nghiệm thu baseline;
ghi rõ **OCR accuracy chưa đánh giá** và theo dõi tại [quality backlog](OCR_QUALITY_BACKLOG.md).
GCC/Tesseract unresolved tiếp tục là blocker production độc lập; không suppress hay
bypass. Bản bàn giao: [M6_BASELINE_HANDOFF.md](M6_BASELINE_HANDOFF.md).
Không rerun toàn suite khi source không đổi và không có vấn đề mới cần xác minh.
Các quyết định implementation bên dưới được giữ làm lịch sử.

Scope: PDF → page text, không business fields/DB/M7/CV thật. Giữ feature/ocr-worker.
Đây là iteration hardening mới được user giao, tối đa3reviewpasses riêng.

1. Exit codes0/1/2 chỉ khi giao result JSON hợp lệ có status success/partial/failed;
   3 cho mọi no-delivery. Request/config lỗi có validated identity hợp lệ được ưu tiên
   failed envelope; nếu identity/envelope/publication không hợp lệ thì3. Receipt safe
   trên stderr có delivered/status, không thêm field vào OCR JSON v1.
2. Clean Windows embedded3.14.8 không pth tới site-packages cũ; wheelhouse lockhash;
   Ubuntu venv include-system-site-packages=false, install --require-hashes offline
   từ wheelhouse mới, interpreter là venv ở tất cả subprocess. Không đổi PATH global.
3. Thay lựa chọn Windows Job Object bằng common Docker Linux child runtime trên
   Windows Docker Desktop và Ubuntu có Docker sẵn. Supervisor ở ngoài container xử
   lý PDF. Không cài Docker/service hoặc thay global daemon config. Mỗi child container:
   memory768MiB(no swap), CPU2, pids64, work tmpfs<=256MiB, rootreadonly/networknone,
   nonroot/capdropALL/no-new-privileges/logdriver-none. Input/control copy streaming
   vào layer trước start rồi runtime read-only, không bind inbox/CV storage/credentials.
   Source/engine/models nằm trong image pin theo SHA256; runtime không download.
4. Child output từ tmpfs qua attach bounded IPC, không writable host output mount;
   container watchdog và parent watchdog độc lập. Stop/kill/wait/remove toàn PID scope.
   Nếu supervisor chết, watchdog vẫn giới hạn thời gian container; không hứa kill
   tức thì hoặc crash-proof. Kiểm chứng behavior bằng bounded synthetic helper sau
   inspect limits, không bằng PDF bomb. Report cgroup memory.peak cho toàn tree;
   OOM nếu memory.events oom_kill tăng hoặc Docker OOMKilled, không suy từ137 đơn thuần.
5. Binary GCC gate độc lập: giữ production BLOCKED nếu unresolved. Nghiên cứu minimal
   reproducer/optimized path/sanitizer trong container riêng; không suppress/-O0/Clang
   hoặc áp PR trộn nội dung. Không tự thay pin/patch binary trước trình bày evidence.
6. Test trên clean Windows và clean Ubuntu: exit matrix, byte boundaries10MiB/+1,
   rotations180/270, nearblank, timeout, output/cleanup; resource enforcement riêng
   có requested/applied/tested. Không xem technical pass là OCR quality readiness.

Dự kiến sửa CLI/config/sandbox, thêm docker adapter/container entrypoint và tests;
provision scripts/Dockerfile/lock evidence cùng README/design/reports. Không thay
schema/version/status ý nghĩa đã duyệt. Lint/type command chưa được cấu hình ở baseline;
kiểm kê rõ, không bịa lệnh đã chạy. Full technical checks mới, XML và source fingerprint.

Docker thực tế từ chối cp vào rootfs readonly kể cả trước start. Điều chỉnh trước test:
trusted bounded staging writer stream task/PDF vào named volume private mới (<=10MiB,
không parse PDF), chmod444 rồi mount volume read-only duy nhất trong PDF container.
Không mount host storage/inbox hoặc output RW. Remove child trước remove input volume;
kill supervisor có thể để stopped container/input volume, phải ghi nhận và operator dọn.

Work tmpfs tách thành /work và /work/engine: engine reserve=min(engine_output_cap,
max_temp/2), phần còn lại cho render/temp; tổng hai tmpfs bằng max_temp. /dev/shm có
cap1MiB riêng được ghi rõ. TXT/TSV/Tesseract logs đi vào engine mount, không chỉ polling.
Control readonly được child manager copy sang /work/task.json để child temp vẫn writable;
input.pdf ở named volume readonly. Quota free bytes và cgroup peak được ghi metrics.
