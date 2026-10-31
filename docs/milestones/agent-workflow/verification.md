<a id="agent-workflow-maintenance-verification"></a>

# Kiểm chứng bảo trì workflow agent

<a id="initial-delivery-evidence--historical"></a>

## Bằng chứng bàn giao đầu — lịch sử

Bằng chứng bàn giao đầu dưới đây chỉ áp dụng cho f0c4049a28da23f79ffcd6c965b048dcba3cc8e3
và ngân sách review trước đó, không kiểm chứng follow-up bên dưới.

<a id="source-and-acceptance"></a>

## Source và nghiệm thu

[Spec](spec.md) đã duyệt, base 81a8a6220e34871ea9fc68aa8e65d19785d0eff9,
branch chore/agent-workflow, checkout E:/cv-compass. Commit cuối xác định trong PR;
SHA256 từng file của payload trước commit giữ tại file local ignored
backend/build/agent-workflow-test/results.json. Không đưa chính file bằng chứng
vào khẳng định fingerprint tự tham chiếu.

Rules, docs đã phân loại, workflow/template/danh mục và ba định nghĩa agent
thực hiện tiêu chí 1–6. Ignore probe thực hiện tiêu chí 7. Check diff/config/link
và bàn giao Git/PR thực hiện tiêu chí 8. Không đổi production code,
application dependency, canonical JSON contract và CI.

<a id="local-verification"></a>

## Kiểm chứng local

Windows 11 / PowerShell, Python 3.14.6 stdlib, codex-cli 0.164.0.
Tester chỉ viết script/output tạm dưới backend/build/agent-workflow-test,
trả lease ghi trước khi Primary sửa; không writer đồng thời.

- Lệnh: py -3.14 E:/cv-compass/backend/build/agent-workflow-test/check.py.
  PASS: 35 file đã sửa/mới, 30 Markdown, 147 relative link; không thiếu target/
  anchor/target ignored. Bốn định nghĩa TOML và 30/30 ignore probe PASS.
- Bốn TOML parse được, đủ/chính xác field dự án và tên vai trò.
  Review nguồn/schema chính thức: [nguồn workflow](../../AGENT_WORKFLOW.md).
- Tester đầu lượt phát hiện ba thiếu sót ignore: secrets/credentials lồng trong docs
  và TOML runtime agent tùy ý. Primary khôi phục loại trừ nhạy cảm sau cùng,
  giới hạn allowlist agent còn ba định nghĩa được duyệt.
- Lệnh: git diff --check. PASS; chỉ thông báo LF-to-CRLF theo checkout policy.
  Không normalize EOL/config của artifact canonical.
- Diff đường dẫn CI/source/build/dependency đóng băng: rỗng. Ba workflow CI hiện có
  không đổi. Application test/formatter: N/A, chỉ đổi docs/config.
- Lệnh: codex app-server generate-json-schema --out <local ignored output>.
  Exit 0. Protocol sinh ra không có phương thức liệt kê custom-agent.
  Native role discovery/dispatch: NOT VERIFIED; không khởi chạy model task bằng role mới.
- Strict startup configuration parse Codex: exit 0, không chẩn đoán unknown-field;
  không xuất cấu hình đã resolve. Lần thử strict features-list trước không được hỗ trợ
  (exit 1), nên thay bằng startup parser được hỗ trợ. Native dispatch vẫn chưa kiểm chứng.
- Không có executable gitleaks: full secret scanner NOT VERIFIED.
  Review file an toàn thủ công và ignore probe không được coi là coverage tương đương.

<a id="review-and-delivery"></a>

## Review và bàn giao

Review chỉ đọc vòng 1 có R1 High: bản nháp thiết kế mới có thể chia sẻ chứa nguồn
từ khách hàng; R2 Medium: bản nháp database còn roadmap hiện hành lỗi thời.
R1 sửa bằng cách giữ bản gốc ignored, publish tham chiếu không chứa nội dung
và cập nhật inbound link. R2 sửa bằng cách tách phần lỗi thời sang kế hoạch lịch sử
rõ ràng và cập nhật tham chiếu đang dùng.
Tester kiểm chứng lại vòng 2 PASS: bản gốc R1 vẫn ignored, không trong tập
dự định/staged; tham chiếu an toàn có thể chia sẻ. Link kế hoạch lịch sử và
tham chiếu hiện hành đã sửa của R2 qua toàn bộ check link/config/ignore/phạm vi.
Không đọc, scan, sao chép hoặc hash bản nháp riêng tư trong lần kiểm tra lại này.
Review chỉ đọc vòng 2 xác nhận R1/R2 đã sửa, không còn blocker. Staging sau đó phát
hiện bốn dòng hard-break Markdown cũ thành trailing whitespace và một dòng trắng
EOF thừa trong archive; đã sửa. Cần kiểm chứng staged cuối và review vòng 3 trước publish.
Đây là bằng chứng trước publish; commit/trạng thái bàn giao chính xác ghi ở PR.
CI là NOT VERIFIED tới khi source được push có run thành công thực tế.
PR giữ dạng nháp khi native runtime hoặc CI bắt buộc còn chưa kiểm chứng.
Không suy ra milestone ứng dụng Active/hoàn thành, production readiness hoặc nghiệm thu
OCR accuracy. Không merge/deploy/đổi GitHub settings.

## Open Questions

Task không yêu cầu quyết định ứng dụng/thiết kế mới. File POC contract/milestone/
handoff local cũ còn thiếu vẫn là khoảng trống đã ghi nhận; thay đổi tích hợp phải
đối chiếu phê duyệt/contract trước khi phụ thuộc chúng.
Native custom-agent discovery cần phiên Codex được tin cậy mới nạp định nghĩa dự án;
chat này không thể khẳng định đã hot-reload.

<a id="follow-up-evidence--2026-10-31"></a>

## Bằng chứng follow-up — 2026-10-31

Bằng chứng lịch sử cho follow-up trước tại
fcd986783e2176a98af4b51a995212c5e402a181. Cách diễn đạt thao tác nhạy cảm được test
trong lượt đó đã bị lượt sửa có giới hạn thứ hai bên dưới thay thế; không phải
quy tắc hiện hành hoặc kiểm chứng cho sửa đổi mới.

Phê duyệt và acceptance criteria mới: [spec follow-up](spec.md#phê-duyệt-follow-up--2026-10-31).
Head local/PR đầu lượt đã đọc: f0c4049a28da23f79ffcd6c965b048dcba3cc8e3.
Base PR: 81a8a6220e34871ea9fc68aa8e65d19785d0eff9. Lượt đó có ngân sách tối đa
ba vòng mới; ba lần review bàn giao đầu không kiểm chứng các sửa đổi này.

Phạm vi implementation: đồng bộ trách nhiệm test và quyền trong phạm vi CI/
thao tác nhạy cảm, PR template chính đã track và link đang dùng.
Implementer chỉ sửa rules/docs/TOML; Primary phân công handoff độc quyền.
Không cho phép thay đổi application, dependency, build/formatter, CI workflow,
GitHub settings, production, release, merge, chuyển Ready, archive mới hoặc trạng thái milestone.

Tester độc lập vòng 1 mới, Windows/PowerShell, Python 3.14.6 stdlib và
codex-cli 0.164.0; chỉ có lease ghi độc quyền cho output kiểm chứng tạm và phần
follow-up này. Không application check nào áp dụng cho sửa docs/config.

- Lệnh: py -3.14 E:/cv-compass/backend/build/agent-workflow-followup/check.py.
  PASS toàn payload PR an toàn: 36 file, 31 Markdown, 150 relative link,
  không thiếu target/anchor/target ignored, bốn định nghĩa TOML và 31/31
  ignore probe. Follow-up đổi 17 đường dẫn. Check cụm từ tạm đầu tiên nhạy với
  wrap dòng; normalize whitespace đã sửa helper và cùng acceptance assertion
  PASS mà không đổi production.
- Check ownership/quyền độc lập PASS: Implementer có test/helper/synthetic fixture
  đi cùng; Tester độc lập kiểm tra acceptance/bổ sung test, không sửa production;
  Reviewer chỉ đọc; Primary phân công một người sửa/lease ghi; không làm yếu
  assertion/check/coverage; CI/hook theo phạm vi rõ ràng. Check đó cũng bao gồm policy
  thao tác nhạy cảm nay đã bị thay thế; không cấp quyền hiện tại cho thao tác chỉ dành cho con người.
- .github/pull_request_template.md chính có thể track; mẫu docs cũ là một điểm trỏ
  tương thích. Bản gốc M10.1 riêng tư vẫn ignored, không trong tập dự định/index;
  không đọc/scan/sao chép/hash. Diff application/dependency/build/formatter/CI so
  với base PR rỗng.
- Lệnh git diff --check và git diff --cached --check: PASS. Index rỗng tại check này;
  whitespace template untracked được kiểm riêng và sạch. Còn cần Primary staging,
  đối chiếu toàn payload staged và review chỉ đọc cuối; PASS index rỗng không
  chứng minh template staged.
- Lần thử native: codex -a never exec --ephemeral --sandbox read-only --cd
  E:/cv-compass --json <bounded custom-role smoke prompt>; exit 0 trong 40.25 giây.
  JSONL thực tế có ba wait event với receiver identity rỗng, không có spawn event
  chứng minh định nghĩa dự án. Phản hồi cuối là
  NATIVE_DISPATCH_NOT_VERIFIED. Khẳng định role token bằng văn bản không phải bằng chứng
  dispatch. Không yêu cầu shell/file/application/publish trong smoke đó.
  Bước tiếp theo: phiên hỗ trợ được tin cậy phải có named-role spawn event thực tế
  cho cả ba định nghĩa dự án; không bypass trust/model/settings.
- Secret scanner: Get-Command gitleaks,trufflehog không tìm thấy; probe command-v
  trong WSL/default và CVCompass-Ubuntu-24.04 hiện có exit 1, không scanner.
  Full secret scan NOT VERIFIED; không cài công cụ. Bước tiếp theo: dùng scanner
  đã duyệt có sẵn/đã provision trên payload Git an toàn, output đã che dữ liệu nhạy cảm.
- SHA256 file an toàn và kết quả mới ở file ignored
  backend/build/agent-workflow-followup/results.json; JSONL/kết quả native có giới hạn
  giữ cùng chỗ. Bản ghi xác định commit đầu lượt cùng payload hiện tại chính xác;
  không khẳng định hash commit tương lai hoặc PASS của lần bàn giao cũ.

Review chỉ đọc độc lập vòng 1 và kiểm chứng staged cuối còn chờ.
CI head follow-up mới là NOT VERIFIED tới khi push và đọc run thực tế.
Native dispatch/full secret scan vẫn NOT VERIFIED như trên.
Giữ Draft; không khẳng định hoàn tất/sẵn sàng merge hoặc milestone ứng dụng.

<a id="second-bounded-revision--phase-1-evidence"></a>

## Lượt sửa có giới hạn thứ hai — bằng chứng pha 1

Phê duyệt: [spec lượt sửa thứ hai](spec.md#phê-duyệt-lượt-sửa-có-giới-hạn-thứ-hai--2026-10-31).
Head local/PR đầu lượt đã đọc: fcd986783e2176a98af4b51a995212c5e402a181.
Base PR: 81a8a6220e34871ea9fc68aa8e65d19785d0eff9. Ngân sách mới tối đa tổng cộng
ba vòng sửa–test–review, kể cả pha dịch sau đó.

Implementation pha 1 khôi phục thao tác chỉ dành cho con người bất kể task,
giữ điều kiện spec với chính xác đường dẫn/mục đích cho CI/security hook,
và tập trung toàn bộ diễn giải trách nhiệm test tại Thực hiện và bàn giao ở root.
Xóa phần trùng trong component; giữ bảng vai trò workflow, giao lỗi production
cho Implementer, lỗi test do Tester thêm cho Tester. Policy/verification/template
trỏ root; mỗi TOML agent có một câu ownership và ràng buộc riêng của vai trò.
Phát biểu cho phép trước đó bên trên là lịch sử đã bị thay thế, không phải quy tắc hiện hành.

Check Tester độc lập mới pha 1 vòng 1: PASS, Windows/PowerShell,
Python 3.14.6 stdlib. Lệnh:
py -3.14 E:/cv-compass/backend/build/agent-workflow-revision2/check_phase1.py.

- Toàn bộ Markdown được track: 69 file, 228 relative link, không thiếu target,
  heading anchor hoặc link target ignored/untracked. Toàn payload PR: 36 đường dẫn;
  pha này đổi 14. Bốn TOML parse được; 31/31 ignore probe PASS.
- Thao tác chỉ dành cho con người vẫn không bao giờ do agent thực hiện, bất kể task;
  CI/security hook trong phạm vi cần chính xác đường dẫn/mục đích trong spec và không
  được làm yếu check. Quét cách diễn đạt đang có hiệu lực ngoài docs/history không
  thấy quyền có điều kiện theo khẳng định. Spec/bằng chứng cũ được đánh dấu rõ là lịch sử đã thay thế.
- Toàn bộ diễn giải ownership ở Thực hiện và bàn giao root; component không còn
  phần trùng và EOF sạch, policy/verification/PR form trỏ root, workflow giữ bảng
  vai trò và chuyển lỗi đúng, mỗi TOML agent đổi có một câu ownership cùng ràng buộc riêng.
- Diff application/dependency/build/formatter/canonical JSON/CI workflow so base PR
  rỗng. PR template chính đã track; bản gốc M10.1 riêng tư ignored, không trong tập
  dự định/index, không được đọc/scan/hash.
- git diff --check và git diff --cached --check: PASS. Index rỗng tại thời điểm check,
  nên Primary còn cần đối chiếu source staged cuối trước review.
  Quét whitespace file mới rõ ràng không phát hiện lỗi. Bộ phân loại tạm được sửa
  để nhận PR template đã duyệt nằm ngoài CI đóng băng; không đổi source hoặc yêu cầu
  vì lần sửa helper đó.
- Đã chuẩn bị inventory dịch, chưa thực hiện: 23 Markdown tiếng Anh cùng
  description/developer_instructions trong ba TOML agent. Tám Markdown an toàn
  được loại trừ rõ ràng có snapshot SHA256 byte working file: năm file docs/history,
  backend/HISTORICAL_VERIFICATION.md, bản nháp database POC và README backend phần lớn
  tiếng Việt/trộn. Giữ byte loại trừ, đường dẫn/URL, lệnh/flag/code fence,
  key/name TOML, tên vai trò, nhãn trạng thái và thuật ngữ kỹ thuật trong pha 2.
  Chính sách ngôn ngữ chỉ tại root là thay đổi nội dung duy nhất được duyệt khi dịch;
  mọi quy tắc bắt buộc khác cần bảng tương đương nguồn–đích.
- Kết quả/fingerprint file mới và inventory byte loại trừ an toàn ở
  backend/build/agent-workflow-revision2/results.json và
  translation-inventory.json, đều ignored. Không bao gồm bản gốc riêng tư và
  không khẳng định hash commit checkpoint tiếng Anh tương lai.

Review chỉ đọc vòng 1 còn chờ trước commit checkpoint chỉ tiếng Anh.
Pha này không dịch hoặc thay application/dependency/build/formatter/CI workflow
hay thao tác chỉ dành cho con người. Lần thử native/full scanner trước vẫn
NOT VERIFIED; không probe nào được chạy lại hoặc đổi nhãn PASS.
CI head mới tương lai vẫn NOT VERIFIED. Giữ Draft, tối đa tổng ba vòng cho
lượt sửa này kể cả dịch; checkpoint tiếng Anh phải có trước pha 2.

## Lượt sửa có giới hạn thứ hai — bằng chứng pha 2

Checkpoint tiếng Anh đã commit và được cho phép dịch:
196a0d5b6f004d9d30576d80113d527d6a6a6dc5.
Pha 2 dùng vòng 2 trong ngân sách chung tối đa ba vòng. Bản dịch chỉ đổi 23
Markdown và description/developer_instructions của ba TOML trong
backend/build/agent-workflow-revision2/translation-inventory.json.
Tám file Markdown loại trừ trong inventory được giữ nguyên byte; không đọc hoặc
hash bản gốc M10.1 riêng tư. Chính sách ngôn ngữ tại root là ngoại lệ nội dung duy
nhất được duyệt; các ràng buộc khác phải tương đương checkpoint tiếng Anh.
Check bản dịch, bảng tương đương từng quy tắc của Tester và review so sánh
chỉ đọc còn chờ; không khẳng định PASS mới, CI mới hoặc dispatch/scanner thành công.

Tester độc lập đã đối chiếu toàn bộ 23 Markdown và trường description/
developer_instructions của ba TOML với commit tiếng Anh bất biến ở trên.
[Bảng tương đương](translation-equivalence.md) kiểm kê 26 file, 333 đơn vị nguồn,
1.069 câu/mệnh đề, không có mệnh đề nguồn chưa ánh xạ; bao gồm cả ngữ cảnh/mẫu,
quyền/chủ thể, thứ tự, điều kiện/ngoại lệ, con số và contract OCR. Đủ hàng không
chứng minh tương đương: kết quả nội dung ban đầu **chưa đạt**, cần sửa T1/T2/T3.

- T1: policy bỏ giới hạn AI bên ngoài, làm rộng phạm vi cấm so với nguồn.
- T2: root gộp xóa file với thao tác hàng loạt và thêm đổi tên vào mệnh đề gốc;
  phải giữ xóa file bất kỳ tách khỏi di chuyển hàng loạt.
- T3: 21 vị trí/nhãn lease/handoff/worktree cần giữ thuật ngữ gốc thay vì chỉ
  diễn giải tiếng Việt. Trường hợp lease120s trong contract vẫn giữ nhãn lease,
  là khác cách đặt khoảng trắng, không phải mất nghĩa hoặc mất thuật ngữ.

Lệnh: py -3.14 -X utf8 E:/cv-compass/backend/build/agent-workflow-revision2/check_phase2.py.
Kiểm tra cấu trúc đã chạy: 70 Markdown được track/mới cùng bảng, 230 relative
link trước khi thêm link bảng bằng chứng này, không thiếu target/anchor; bốn
TOML parse được, key/name và cấu hình agent không đổi; 31/31 ignore probe và
8/8 snapshot SHA256 byte file loại trừ khớp. Code fence, inline code và URL
ngoài giữ nguyên. Số vòng giữ đúng: từ chỉ thứ tư đổi sang chữ số 4 trong tiếng
Việt là tương đương, không tăng ngân sách ba vòng. Nhãn command/placeholder,
Conventional Commit và thuật ngữ như Hibernate/CI/lease/handoff được phép giữ
tiếng Anh; 32 tín hiệu chuỗi Latin ban đầu đã được đọc và đều là thuật ngữ,
nhãn/lệnh hoặc câu tiếng Việt pha thuật ngữ, không phải đoạn văn tiếng Anh bỏ dịch.

Diff-check unstaged/staged trên tập đường dẫn dự định: PASS; quét whitespace
bảng mới sạch. Index hiện rỗng; Primary còn cần kiểm tra payload staged cuối.
Payload commit/index/dự định không đổi application/dependency/build/formatter/
canonical JSON/CI so với base và checkpoint tiếng Anh. Working tree có thay đổi
ngoài phạm vi tại backend/src/main/resources/application.yaml và
backend/src/main/resources/mapping/experience-config-v2.json; chỉ nhận diện tên,
giữ nguyên, không đọc/hash/sửa/stage/revert. Không khẳng định working tree sạch.
Bản gốc M10.1 riêng tư vẫn ignored và ngoài index, không đọc hoặc hash.

Kết quả an toàn mới ở backend/build/agent-workflow-revision2/phase2-results.json;
clause-coverage.json giữ kiểm kê nguồn, technical-term-misses.json giữ vị trí cần
sửa. Chính sách ngôn ngữ root là ngoại lệ nội dung duy nhất đã duyệt. Chưa nghiệm
thu bản dịch; chờ Implementer sửa có phạm vi, Tester kiểm lại và Reviewer vòng 2.
Native dispatch/full secret scan/CI head mới vẫn NOT VERIFIED; không chạy lại
probe hoặc suy ra kết quả mới từ bằng chứng cũ. Ngân sách chung vẫn tối đa ba vòng.

Sau handoff sửa có phạm vi, Tester đã đọc lại các thay đổi và kiểm chứng:
T1 giữ đúng AI bên ngoài; T2 giữ xóa file bất kỳ tách khỏi di chuyển file hàng
loạt, không thêm đổi tên; T3 khôi phục toàn bộ 21 vị trí/nhãn lease/handoff/worktree.
Các finding T1/T2/T3 đều **đã sửa và kiểm chứng**. Trường hợp lease120s vẫn nguyên
như source đích ban đầu và được chấp nhận về định dạng, không đổi contract.

Bảng đã cập nhật từ bản dịch sau sửa, giữ 26 file/333 đơn vị/1.069 mệnh đề và
không bỏ mệnh đề nguồn; vị trí đích phản ánh dòng hiện tại. Đã kiểm tra cả mức
bắt buộc và điều kiện phủ định: cấu trúc cấm với may được phân loại là cấm,
không phải cho phép. Ngoại lệ nội dung duy nhất là chính sách ngôn ngữ root.
Kiểm tra ngữ nghĩa cuối **PASS**; bảng không tự đặt quy tắc hoặc nghiệm thu thay người dùng.

Rerun check_phase2.py trên bản ghi cuối: 70 Markdown/231 relative link, không
target/anchor hỏng; link có fragment trong phạm vi trỏ tới heading tiếng Việt
thực tế, không chỉ dựa vào alias cũ. Bốn TOML/key/name/config, 31 ignore probe,
tám snapshot raw byte loại trừ và code fence/inline code/URL đều PASS. Cơ chế
phân vai root-only, thao tác chỉ dành cho con người, CI theo spec chính xác và
ngân sách ba vòng vẫn tương đương. Không dịch code/lệnh hoặc thay giới hạn.

Payload an toàn không có thay đổi đóng băng; hai cấu hình working tree ngoài
phạm vi nói trên vẫn được giữ nguyên và ngoài index, không đọc/hash/nội dung diff.
Diff-check trên tập dự định và quét whitespace bảng mới PASS; index còn rỗng,
Primary sẽ stage chính xác tập tài liệu trước khi Reviewer vòng 2 kiểm lại.
Chưa kết luận source staged, CI head mới, native dispatch hoặc full secret scan
PASS. Ngân sách không đặt lại: pha dịch vẫn thuộc vòng 2 của tối đa ba vòng chung.
