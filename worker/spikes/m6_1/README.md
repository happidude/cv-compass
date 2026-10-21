# M6.1 — component spike, không phải production worker

Chỉ sinh một native PDF và một scan PDF tổng hợp thông thường vào TemporaryDirectory. Không có tham số input PDF/CV ngoài. Không dùng resource bomb. Không suy luận OCR quality từ fixture này.

## Contract checks (chạy trước spike)

Dùng Python3.14.8 và jsonschema4.26.0 đã provision:

```text
python worker/design/verify_contract.py
python -m unittest discover -s worker/spikes/m6_1 -p test_stack_smoke.py -v
```

Canonical v1 ceilings: worker/design/ocr-v1.limits.json. Reference config resolver và anti-drift tests đọc manifest này. Chưa có production configuration/pipeline.

## Windows (môi trường riêng đã chuẩn bị)

Từ worktree root, runtime chính thức embedded Python3.14.8 tại worker/build/m6-1-spike/python3148/python.exe. File ._pth chỉ trỏ đến .venv riêng của spike; không nâng Python/global. Tesseract5.5.3 installer upstream được verify SHA256 rồi giải nén bằng7-Zip administrative extraction, không chạy installer Tesseract. Models pin revision trong stack_smoke.py; font DejaVuSans2.37.

```powershell
& worker/build/m6-1-spike/python3148/python.exe worker/spikes/m6_1/stack_smoke.py --tesseract worker/build/m6-1-spike/tesseract-portable/tesseract.exe --models worker/build/m6-1-spike/models --font worker/build/m6-1-spike/dejavu-fonts-ttf-2.37/ttf/DejaVuSans.ttf --report worker/build/m6-1-spike/stack-windows.json
```

Report isolation=not_enforced. Đây chỉ là diagnostic, không resource safety evidence. --profile production luôn bị từ chối trước engine/fixture (exit3); spike không có production adapter.

## Ubuntu24.04

Dockerfile.ubuntu chỉ là provisioning image spike: Ubuntu digest pin, Python3.14.8 và Tesseract5.5.3 build source, direct dependency pins, nonroot runtime. Không Docker Compose/service/deployment.

```text
docker build --progress=plain -f worker/spikes/m6_1/Dockerfile.ubuntu -t cv-compass-m61-spike:local worker/spikes/m6_1
```

Runtime đã dùng network none, read-only root, cap-drop ALL, no-new-privileges, memory1GiB/swap1GiB, cpu2, pids64, tmpfs /tmp256MiB. Mount chỉ design/ và spike/ read-only, font tổng hợp đã provision read-only và report directory riêng writable. Không mount inbox/storage/CV thật hoặc backend .env. Xem M6_1_STACK_SPIKE.md và docker-applied-settings.json để biết lệnh/kết quả thực tế.

Image có /models/{vie,eng,osd}.traineddata; font cùng hash Windows được mount /fixture-font.ttf. Ví dụ command trong container (đặt mounts/caps như trên trước khi chạy):

```text
python3 /design/verify_contract.py
python3 -m unittest discover -s /spike -p test_stack_smoke.py -v
python3 /spike/stack_smoke.py --tesseract /usr/local/bin/tesseract --models /models --font /fixture-font.ttf --report /report/stack-ubuntu.json
```

## Những điều spike thực sự kiểm tra

Native text, scan không native text, render300DPI, Tesseract vie+eng/OEM1/PSM3 tạo txt+TSV cùng invocation, sample diacritics/contact, TSV score có thật, OSD, source digest bất biến và cleanup. Text/TSV bật bằng -c tessedit_create_txt=1 / tessedit_create_tsv=1, không phụ thuộc config aliases txt/tsv trong model directory. Tesseract semver parser chấp nhận khác prefix v/suffix build hai OS nhưng vẫn đòi chính xác5.5.3 và có negative tests.

Tesseract child nhận env allowlist, không DB/cloud credentials. Report chỉ chứa versions/hash/check flags/timing và diagnostics counts, không OCR text hoặc physical paths. Verification report là artifact riêng, không OCR JSONv1.

Compiler warning upstream/provisioning được giữ trong build logs; không disable lint, không đổi assertion để tạo PASS. Xem báo cáo để biết warning chưa kết luận và giới hạn. Chưa có source selection, timeout supervisor/process-tree kill, result-packing/partial pipeline, corpus evaluation hoặc M7.