# M6.2 — Điều tra GCC/Tesseract (2026-10-18)

**Production binary BLOCKED — chưa kết luận warning vô hại.** Không suppress warning,
không đổi compiler/optimization để làm sạch log, không patch upstream trong nhiệm vụ này.

## Tái hiện và provenance

Ubuntu24.04.5, x86_64, GCC `13.3.0-6ubuntu2~24.04.1`, CMake ghi tại artifact.
Tesseract source tag5.5.3, archive SHA256:
`9218e62793116d42a9f6d14cd9348518b27f382096eea3d0f2d1a24616bb5884`.
Image spike gốc `sha256:6b5d23e3d54c6dd0a6223a2e6c0839bedc9041255a470ef42ff2de14088cd749`.

Chạy `spikes/m6_2/investigate-gcc.sh` trong image gốc với report mount riêng. Cấu hình
lại đúng Release/BUILD_TRAINING_TOOLS=OFF/BUILD_TESTS=OFF, chỉ thêm export compile commands
để ghi chứng cứ. Biên dịch đúng translation unit `serialis.cpp` theo command CMake.
Exit0 nhưng tái hiện **40 `-Wstringop-overflow` warnings**, cùng vị trí/call chain
với log full build M6.1. Đây không phải build lại toàn binary và không phải kiểm chứng
provenance compiler của Windows prebuilt binary.

Lệnh đầy đủ ở `build/m6-2/gcc/serialis-command.txt`, toàn compile DB ở
`compile_commands.json`, `CMakeCache.txt`, compiler target/configure flags ở `compiler.txt`.
Flags chính: `-O3 -DNDEBUG -std=gnu++20 -DCMAKE_BUILD -DHAVE_CONFIG_H`, SIMD defines
AVX/AVX2/AVX512F/FMA/SSE4_1/OPENMP_SIMD; upstream PCH include, include paths và
`-D_SILENCE_STDEXT_HASH_DEPRECATION_WARNINGS=1` có sẵn upstream, không phải suppress
GCC do task này thêm. Không có `-Wno-stringop-overflow`.

Full diagnostic: `build/m6-2/gcc/serialis-diagnostic.log` (không lọc); full historical
build: `build/m6-1-spike/ubuntu-build.log`. Source exact archive được lưu private tại
`build/m6-2/gcc/source/{helpers.h,serialis.h,serialis.cpp}` để đối chiếu line numbers.
Compiler/GCC warning không bị redirect bỏ: stdout+stderr giữ nguyên trong artifact.

## Vị trí và phân tích (chưa phải kết luận an toàn)

`helpers.h:198–207` ReverseN có loop byte swap phụ thuộc `num_bytes`; assert cho1/2/4/8
bị loại bởi NDEBUG. Warning tại `:204` và `:205`: compiler suy luận vectorized stores
vượt destination uint32_t. Call chain: `serialis.cpp:233` FReadEndian →
`serialis.h:91` DeSerialize<T>(data,sizeof(T),count) → `serialis.cpp:99–100`
DeSerialize(std::string&) với biến `uint32_t size`.

Trong path này, source-level sizeof(uint32_t)=4 và count mặc định1; loop ReverseN
về lý thuyết chỉ swap trong4 bytes, còn FRead trả required_size/size. Điều đó gợi ý
liên quan phân tích inlining/vectorization, **chưa chứng minh** false positive cho
mọi call site, malformed traineddata hoặc optimized machine code. Chưa có minimal
reproducer được upstream xác nhận, sanitizer/assembly review toàn phạm vi hoặc fix
đã review/pin lại. Không dùng technical OCR pass làm chứng cứ memory safety.

## Căn cứ upstream

- [Tesseract5.5.3 helpers.h](https://github.com/tesseract-ocr/tesseract/blob/5.5.3/src/ccutil/helpers.h),
  [serialis.cpp](https://github.com/tesseract-ocr/tesseract/blob/5.5.3/src/ccutil/serialis.cpp).
- [GCC13.3 warning documentation](https://gcc.gnu.org/onlinedocs/gcc-13.3.0/gcc/Warning-Options.html):
  -Wstringop-overflow có nhiều level và phụ thuộc object-size/optimization. Tài liệu
  tổng quát không đủ để gọi warning cụ thể này là false positive.
- [Upstream PR4527](https://github.com/tesseract-ocr/tesseract/pull/4527) đề xuất switch
  cho chính ReverseN nhưng còn **open, chưa merged**, đồng thời trộn web interface.
  [Discussion](https://github.com/tesseract-ocr/tesseract/pull/4527#issuecomment-4070334372)
  không coi PR là accepted fix.
  Contributor yêu cầu reproduction với compiler/options; collaborator đề nghị tách PR.
  Metadata, patch và comments API public được lưu nguyên ở `build/m6-2/gcc/upstream-*`.

Không áp dụng patch chưa được duyệt hoặc thêm Flask. Bước tiếp theo cần iteration
riêng để tạo minimal reproducer/audit optimized path hoặc xác minh bản upstream đã
sửa, rồi build đúng provenance và re-test hai OS. Hiện cả đường chạy production đều
fail closed; Windows prebuilt không được miễn gate vì log warning xuất phát từ Linux.

## Hardening iteration — bằng chứng bổ sung

Recompile full serialis.cpp trong iteration mới vẫn40warnings với GCC13.3.0/
CMake3.28.3/-O3/-DNDEBUG/std=gnu++20, exact Tesseract5.5.3 source SHA256 cũ.
Artifacts mới: build/m6-2-hardening/gcc-full (compiler/cache/compile_commands/diagnostic/source).

spikes/m6_2_hardening/reverse_n_reproducer.cpp là reduced call path từ Apache-2.0
upstream (không replacement binary): ReverseN/FReadEndian/template DeSerialize và
uint32 size. Reduced program không tái hiện warning khi compile -O3/-DNDEBUG;
assembly đã lưu tại build/m6-2-hardening/gcc/reproducer.s. Optimizer có thể inline/
constant-propagate reduced program khác full TU; chưa map chính xác diagnostic stores
vào optimized machine-code path production. Không gọi reduced probe là minimal
reproducer thành công hoặc bằng chứng demonstrated compiler false positive.

ASan+UBSan cùng GCC/-O3 trên517bounded cases (short reads0..512bytes và swaps1/2/4/8)
exit0, không sanitizer finding. Scope chỉ reduced program/valid initialized reader
states, không full engine/mọi malformed traineddata/PDF. Container networknone,
512MiB/no-swap/CPU2/PIDs32, core0; riêng compile-analysis tmpfs exec để chạy binary
helper trusted, không đổi noexec của PDF processing adapter. Lần đầu test gặp tmpfs
noexec/Permission denied, đã sửa đúng mount cho compile harness và rerun; không
suppress hoặc coi đó là defect OCR. Full commands/build/run logs được giữ.

Kết luận vẫn **UNRESOLVED** cho full TU/binary. Không confirmed defect, cũng không
claim false positive. Không đổi compiler, giảm optimization hoặc áp PR4527/patch/
pin mới. Production tiếp tục BLOCKED độc lập với technical enforcement tests.
