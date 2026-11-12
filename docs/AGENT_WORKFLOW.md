# Workflow agent

Task rõ là phê duyệt; thiếu thì chặn phần phụ thuộc. Mục tiêu: đúng trước, rồi ít vòng và ít token.

## Nguyên tắc tiết kiệm

- Primary đọc spec/code liên quan **một lần** và chuyển tri thức qua brief. Subagent đã được nạp sẵn
  `CLAUDE.md`/`AGENTS.md`; không đọc lại toàn repo hay tài liệu ngoài brief.
- Feature lớn chia slice: mỗi PR một hành vi, mục tiêu ≤ khoảng 400 dòng code thay đổi (không tính test).
- Mỗi vòng chỉ chạy affected check; full gate một lần trên source cuối.
- Check do `verifier` chạy; log ghi file, chỉ trả tóm tắt. Log dài không vào hội thoại.
- Không review lại thay đổi chỉ format; delta review không đọc lại phần đã review.

## Chọn mức

| Mức | Điều kiện | Luồng | Review |
| --- | --- | --- | --- |
| 1 | Docs/format/comment/sửa hiển nhiên không đổi nghiệp vụ | Primary tự sửa → check cấu trúc | Không |
| 2 | Hành vi thường; quy tắc/AGENTS.md/policy/template/CI/security hook luôn tối thiểu mức 2 | Implementer + test → verifier affected → reviewer | 1 lượt + 1 delta |
| 3 | Persistence/migration, auth/security, logic dữ liệu cá nhân, API/OCR contract, transaction/async, cô lập/sandbox worker | Plan ngắn được chủ dự án duyệt → implementer + test → verifier full → reviewer | 1 lượt + 1 delta |

Developer được ép mức; nghi ngờ thì chọn cao hơn. Docs/config chỉ check cấu trúc, không test giả.
Tester riêng chỉ khi brief ghi lý do (ví dụ migration/contract cần kiểm chứng độc lập);
mặc định implementer viết test đi kèm, reviewer chỉ ra test còn thiếu.

## Brief (≤ 15 dòng)

```text
Mục tiêu/acceptance: <tiêu chí kiểm được>
Spec: <link, nguồn/ngày duyệt>
Mức/lý do: <1|2|3>
Path được sửa / loại trừ: <...>
Base: <SHA, branch/worktree>
Ngữ cảnh đã biết: <file/hàm/contract chính Primary đã đọc, để subagent không tìm lại>
Check affected: <lệnh cụ thể>
```

Primary chịu trách nhiệm bằng chứng; subagent không tự delegate/mở task mới.
Publish: writer commit + push feature branch bằng `git push -u origin <branch>` (không push `main`, không force);
implementer mở Draft PR một lần (`gh pr create --draft`), cập nhật mô tả khi gate xong.
Lệnh push vẫn hỏi xác nhận (settings không auto-allow) cho tới khi `main` có branch protection.
Một writer tại một thời điểm: chờ writer/check dừng hẳn, đọc status/diff rồi mới giao tiếp.

## Review và vòng sửa

- Lượt đầu: toàn diff so với base + caller/contract/test bị ảnh hưởng; kiểm acceptance, tính đúng,
  ranh giới module, transaction, security, privacy/log, concurrency, test thiếu.
- Delta: chỉ thay đổi từ SHA đã review + finding còn mở.
- Finding: ID, severity, file:line, ảnh hưởng, bằng chứng, cách sửa. Không bịa lỗi từ check thiếu.
- **Chặn bàn giao:** Critical (lộ/mất dữ liệu), High (sai lớn về tính đúng/security/acceptance).
  **Medium:** sửa ngay nếu thuộc acceptance và sửa nhỏ; còn lại ghi `## Follow-up` trong PR.
  **Low:** ghi nhận.
- Sau 1 lượt + 1 delta vẫn còn Critical/High: BLOCKED, giữ bằng chứng, dừng và hỏi chủ dự án.
  Không tự thêm vòng, không tự bỏ finding.

## Gate bàn giao

Đạt acceptance, hết Critical/High, verifier PASS trên source cuối, review theo mức;
không secrets/CV/runtime artifact, `git diff --check` sạch. [Mẫu bằng chứng](templates/verification.md).
CI bắt buộc chưa xanh thì không nói sẵn sàng merge; CI bị chặn (billing/startup) ghi NOT VERIFIED và mở Draft.
**Ngoại lệ tạm thời** (chủ dự án duyệt 2026-11-08, đến khi billing Actions được gỡ): bàn giao được khi
local full gate PASS trên source cuối; CI ghi NOT VERIFIED, không ghi PASS.
[Git/PR](CV_COMPASS_GIT_WORKFLOW.md).

## Custom agent (Claude Code)

`CLAUDE.md` import `AGENTS.md` (nguồn quy tắc duy nhất); `backend|frontend|worker/CLAUDE.md`
import AGENTS component. Subagent ở `.claude/agents/`, gọi tuần tự:

| Agent | Model | Việc |
| --- | --- | --- |
| implementer | sonnet | Code + test trong path của brief |
| tester | sonnet | Chỉ khi brief yêu cầu; chỉ sửa test/fixture |
| verifier | haiku | Chạy check, trả tóm tắt PASS/FAIL; không sửa file |
| reviewer | inherit (model phiên chính) | Review chỉ đọc, trả finding |

`maxTurns` trong frontmatter là trần an toàn, chỉnh theo số liệu thực tế. Reviewer/verifier bị khóa
Write/Edit ở mức công cụ; Bash vẫn mở để đọc Git/chạy check nên phần chỉ đọc của Bash là chỉ dẫn.
Quyền dùng chung ở `.claude/settings.json`; thiết lập cá nhân ở `.claude/settings.local.json` (ignore).
`.codex/` giữ tạm để tương thích lịch sử, không còn là cấu hình chính; xóa khi chủ dự án duyệt.

## Codex qua codex exec

Primary (Claude) có thể giao phần implementer cho Codex chạy không tương tác bằng
`scripts/codex-task.ps1 <task-id> <brief-file> [-Resume <session-id> <followup-file>]`.
Codex chạy trong worktree riêng `..\cv-compass-wt\<task-id>` (sandbox `workspace-write`, không mạng,
môi trường `core`, bỏ qua user config); kết quả theo `scripts/codex-result.schema.json`, event/log ở
`build/codex/<task-id>/` của checkout chính. Script tự kiểm sandbox của turn cuối (kể cả resume), in ≤ 15 dòng.
Model theo vai (`-Role`, id tra bằng `codex debug models`, chủ dự án chốt 12/11/2026): `code` (mặc định) =
`gpt-6.1-sol` mức `high`; `gate` = `gpt-6.1-sol` mức `low`. Ghi đè bằng `-Model`/`-ReasoningEffort`;
`-DryRun` chỉ in model/lệnh, không gọi Codex và không tính vào số lần gọi.
**Luôn chạy `codex-task.ps1` từ checkout chính `E:\cv-compass`**, không từ worktree task, để Codex
không sửa được chính script/guard đang kiểm nó.

| Việc | Codex | Claude |
| --- | --- | --- |
| Chuẩn bị | Không | Tạo worktree/branch, chạy `npm ci` trong `frontend` trước khi gọi Codex |
| Code + test trong path của brief | Có | Không ghi vào worktree khi Codex đang chạy |
| Check | Check frontend; unit test Python bằng `py -3.14` (không cần mạng/DB/Docker) | `verifier` chạy backend/worker/llm-worker và full gate |
| Review | Không | `reviewer` theo mức |
| Publish | Không commit/push | Commit, push feature branch, Draft PR |

- Codex được chạy unit test Python thuần (vd. `llm-worker/`, `worker/` phần không cần Docker) bằng
  `$env:PYTHONPATH='src'; py -3.14 -m unittest discover -s tests` để phản hồi nhanh. `py -3.14` trên Windows
  chỉ là kiểm nhanh; môi trường có giá trị quyết định là CPython 3.14.8 trong WSL `Ubuntu-24.04`
  (full gate/verifier). Sandbox không thấy Python thì ghi check đó trong `checks_run` với `result: NOT_RUN`
  và lý do ở `notes`, không cài gì (12/11/2026 sandbox báo "No installed Python found").
  Quyền này không nới quy tắc khác: vẫn cấm thêm/xóa/nâng dependency và sửa path bảo vệ (bên dưới).
- Tối đa **2 lần gọi/task** (lần đầu + 1 resume theo session id, không `--last`); script chặn lần thứ 3.
  Vẫn chưa đạt thì BLOCKED, giữ bằng chứng và hỏi chủ dự án.
- Brief/follow-up theo mẫu brief ở trên; không chứa CV thật, PII, secret hay giá trị env.
  Lệnh check frontend trong brief cho Codex trên Windows dùng `npx.cmd` (vd. `npx.cmd prettier --check src tests`)
  hoặc `npm run <script>`, không `npx` trần (trên Windows có thể phân giải sang shim `npx.ps1` không chạy được).
  Script không truyền `TEST_DB_*` hoặc biến dạng secret/token/key vào môi trường Codex.
- Cấm thêm/xóa/nâng dependency. Thay đổi so với base SHA của task (HEAD worktree trước lần gọi đầu,
  gồm commit, chưa commit và untracked) chạm `build.gradle*`, `settings.gradle*`,
  `gradle-wrapper.properties|jar`, `libs.versions.toml`, `package*.json`, `.npmrc`, `pyproject.toml`,
  `requirements*.txt|lock|in`, `Dockerfile*` là **High**: script trả exit 3, Primary dừng và hỏi chủ dự án,
  không tự chấp nhận.
- Path bảo vệ, cùng cách so với base SHA và cùng xử lý **High**/exit 3: `AGENTS.md`, `CLAUDE.md` (mọi thư mục),
  `.claude/`, `.codex/`, `.github/`, `docs/AGENT_*.md`, `scripts/codex-task.ps1`, `scripts/full-gate.ps1`,
  `scripts/codex-result.schema.json`, `worker/spikes/m6_2_hardening/download_locked.py` (tải artifact khóa của worker gate).
- Primary đọc `status`/`files_changed`/`open_questions` rồi tự đọc diff; `checks_run` của Codex không
  thay verifier. Full gate: `scripts/full-gate.ps1` (xem [kiểm chứng](VERIFICATION.md#full-gate-và-chuỗi-pr)).

## Nhiều phiên chạy không người

Khi chủ dự án duyệt cho hai phiên Primary (A, B) chạy song song không người trông (như đêm 10–11/11/2026),
hai phiên phối hợp qua thư mục `..\cv-compass-wt\_coord\` (ngoài Git, không chứa CV thật/PII/secret):

- **Báo cáo và tín hiệu xong:** mỗi phiên ghi `<X>-report.md` (khảo sát/finding) rồi tạo `<X>.done`
  (một dòng: phiên + giờ). Phiên kia chỉ đọc report sau khi thấy `.done`; không sửa file `_coord` của phiên khác.
- **Plan:** sau khi có cả `A.done` và `B.done`, phiên được giao viết `plan.md` rồi tạo `plan.done`.
  Plan ghi nguồn duyệt (ai, lúc nào), giới hạn cứng giữ nguyên, chuỗi PR (slice/branch/worktree/mức/base),
  phần để sau và **bảng quyền sở hữu file**. Phiên kia chỉ bắt đầu ghi code sau `plan.done`.
- **Quyền sở hữu file:** mỗi path (glob) đúng một phiên; dòng cuối "mọi path khác" thuộc một phiên rõ ràng.
  Phiên không bao giờ ghi path của phiên kia; việc cần chạm path của phiên kia thành follow-up/PR sau,
  ghi trong `## Cần duyệt` của báo cáo cuối.
- **Khóa Gradle:** chỉ một lượt Gradle (affected check hoặc full gate) tại một thời điểm, qua `_coord/gradle.lock`
  (nội dung: tên phiên + giờ). Có khóa thì chờ; khóa cũ hơn **90 phút** coi là bỏ rơi và được tiếp quản
  (ghi lại việc tiếp quản); xóa khóa ngay khi lượt chạy xong, kể cả khi lỗi. Helper dùng chung:
  `_coord/with-gradle-lock.sh <phiên> <lệnh...>`.
- **Kết thúc:** mỗi phiên ghi `<X>-final.md`: PR đã mở (Draft) và thứ tự merge, kiểm chứng/review/đo,
  full gate, slice BLOCKED, `## Cần duyệt`, việc chủ dự án cần làm. Chủ dự án đọc `A-final.md`/`B-final.md` buổi sáng.
- Mọi quy tắc khác giữ nguyên: một writer mỗi working tree, không merge/Ready/push main/force push,
  không tự xóa branch/worktree/file, quyết định ngoài plan ghi Open Questions thay vì tự làm.
