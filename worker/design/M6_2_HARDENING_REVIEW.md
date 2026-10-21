# M6.2 Hardening — Review record

Base5c06070955e2313f041be47ac313d97b2c079bea, feature/ocr-worker,
E:/cv-compass-ocr-design. New user-authorized iteration, one agent Developer/Reviewer,
3passes maximum. Actual committed/staged/unstaged Git diff is empty because worker
changes remain untracked; intended new/modified files were read directly and compared
against previous source manifest. Only worker scope; no Java/business/DB/M7/CV thật.

Reviewed final code/data fingerprint54files: `59484426a6144f3818ea8ecc7ef833029d6a9da3d32fdc2bbb4ee2363143e5d4`.

| Finding | Severity | Exact final location | Problem/evidence | Resolution |
|---|---|---|---|---|
| H-R1 | High | src/cv_compass_worker/docker_sandbox.py:81 | Local Docker endpoint check occurred after staging could stream input; inference from call order | Gate before all staging, regression remote endpoint rejects before command/source read; fixed+verified |
| H-R2 | Medium | src/cv_compass_worker/docker_sandbox.py:184; pipeline.py:90 | Cleanup failure could replace primary failure/result and lose intake halt | Preserve primary, cleanup ledger/exception flag, warning+lock/halt; five regression tests include ledger disk-full injection; fixed+verified |
| H-R3 | Medium | src/cv_compass_worker/docker_sandbox.py:124 | Private OCR result packet named .log risks log collection | Explicit IPC .json, Docker logdriver-none, real-container assertions *.log lacks text/name/path; fixed+verified |

Pass1: inspect actual source/callers/tests/config/contract; H-R1/H-R2 confirmed. Return
to Developer for fixes and tests. No contract/schema major drift, no business mapping.
Pass2: re-read fixes, regression results, adapters/caps/CLI/examples; H-R3 identified,
fixed in Developer role. Final source frozen, clean-env full Windows80/0/0/0 and Ubuntu
subset67/0/0/0 rerun after last fix.13required Ubuntu checks remain blocked, not skipped.
Pass3: final evidence/report/log/XML/hash/whitespace/ownership review. No new Critical/
High/Medium implementation finding in tested diagnostic scope. External gate blockers
remain per report: GCC unresolved, full Ubuntu authorization, unverified I/O/VPS and
quality. Not READY FOR PR/production/M6 completion. No fourth pass or further code edits.

Architecture, input security, resource boundaries, child IPC, output limits, failure
handling, cleanup/concurrency reviewed. DB transactions/Modulith/migrations N/A.
CI unavailable, never claimed. No credentials/CV data in intended Git files. No commit/
push/merge/worktree deletion.3/3passes used; reports are source/evidence, not external audit.

## Verification-only batch — 2026-10-20

Đây là batch mới sau khi developer cấp quyền Ubuntu WSL/Docker integration, không phải
pass4 của implementation batch trên. Developer role chỉ provision môi trường test riêng,
fresh-install hash lockfiles và chạy evidence; không đổi 54 file source/test/contract.

Reviewer pass1/3: đối chiếu 54/54 hash với manifest
`59484426a6144f3818ea8ecc7ef833029d6a9da3d32fdc2bbb4ee2363143e5d4`; đọc full
terminal log và JUnit XML Ubuntu mới (80tests,0failures,0errors,0skipped), isolation JSON,
fresh-install/pip-check logs và label snapshots. Xác nhận13 checks trước đây thiếu đã chạy,
không resource test tồn đọng; no new Critical/High/Medium implementation finding. Review
báo cáo phát hiện trạng thái Ubuntu cũ dễ gây hiểu nhầm và đã sửa câu tổng quan/blocker,
giữ số liệu subset như historical evidence. Không cần pass2/3 vì không có code finding/
code fix. GCC vẫn unresolved; OCR quality NOT VERIFIED; production/M6 vẫn BLOCKED.

## Bàn giao baseline theo phạm vi mới — 2026-10-21

Developer điều chỉnh nghiệm thu: M6 baseline PDF → text theo trang, không yêu cầu
corpus/ground truth/CER-WER/tuning; **OCR accuracy chưa đánh giá**, quality chuyển
backlog riêng. GCC/Tesseract unresolved vẫn chặn production độc lập. Các kết luận
completion/quality gate trong batch lịch sử phía trên không phải tiêu chí baseline
mới. Không thay source/contract/tests/config/locks, không bắt đầu M7.

Developer role: rà CLI/pipeline/child/PDF/OCR/layout/config/contracts/sandbox/Docker
watchdog và regression assertions thực tế. Cập nhật README, hardening decisions/report;
thêm M6_BASELINE_HANDOFF.md (chức năng, cách chạy fixture, exit/result, evidence và
giới hạn) cùng OCR_QUALITY_BACKLOG.md. Giữ nội dung cũ làm lịch sử và snapshot tài liệu
trước chỉnh sửa ở thư mục task riêng ngoài repository để review diff.

Reviewer vòng1/3: actual committed/unstaged/staged Git diff rỗng vì worker còn untracked;
đọc intended new files và no-index diff với snapshot. Worktree feature/ocr-worker,
HEAD/base5c06070955e2313f041be47ac313d97b2c079bea, git status `?? worker/`.
Đối chiếu54/54file manifest SHA256
`59484426a6144f3818ea8ecc7ef833029d6a9da3d32fdc2bbb4ee2363143e5d4`:0mismatch.
Đọc lại XML/log evidence cuối Windows/Ubuntu ngày20/10: mỗi OS80tests/0failures/
0errors/0skipped, hash khớp report/handoff. Không rerun suite theo yêu cầu khi source
không đổi; đây là reuse evidence có source parity, không phải tests mới ngày21/10.

Không có Critical/High/Medium finding mới trong diagnostic baseline scope; không còn
thiếu chức năng baseline đã giao cần thay pipeline. Bản hướng dẫn CLI/fixture được
review với source và tests đã chạy; không claim đã chạy riêng snippet handoff này.
`git diff --check` và no-index whitespace check cho5tài liệu nội dung không diagnostic;
relative Markdown links0missing. No-index check dùng `cr-at-eol` nhận CRLF Windows;
lần check đầu phân loại CRLF thành trailing whitespace không phải source/GCC finding.
Review record này được đọc/kiểm tra riêng sau khi ghi. Java/DB/Gradle/Modulith N/A.

Kết luận: bản bàn giao baseline dùng thử đủ evidence kỹ thuật để developer nghiệm thu;
**OCR accuracy chưa đánh giá; production-ready BLOCKED_GCC_WARNING_UNRESOLVED**.
Host supervisor cap/hard I/O/VPS vẫn NOT VERIFIED và được nêu ở handoff. CI chưa chạy
cho source uncommitted. Không commit/push/merge/force-add, không đổi môi trường,
không CV thật hoặc M7. Một vòng review dùng cho đợt docs này, không external audit.
