# M7.3 → M7.5 integration — implementation, not acceptance evidence

> Historical v1 design/evidence. New results use `cv-compass.ocr/2.0` and only `raw_text`; see `OCR_V2_RAW_TEXT.md`. Existing v1 snapshots/outcomes remain valid.

Local admission extension: the default contract below remains synthetic-only.
An explicitly configured local deployment sends `synthetic_only=false` and
`local_only=true` in claim data; the worker must opt in to the same mode in
`pull.toml`. Local admission snapshots bind actual document hash/size and measured
profile provenance with `admission_mode=local`, without a fabricated `fixture_id`.
No result schema, auth, lease, fencing, replay or isolation rule changes. See
[LOCAL_OCR.md](../../backend/LOCAL_OCR.md); this is not a production gate waiver.

Base origin/main `afba013d0e40355b13f4adf9dbd59302d162988d`. Dedicated branch
feature/m7-integration. No new migration or broker, no changes to old migrations,
no candidate mapping, frontend or worker DB access. Accuracy has not been evaluated;
quality evaluation remains backlog. GCC/Tesseract unresolved still blocks production.

## M7.3: authenticated backend HTTP

Base `/internal/ocr/v1`: POST `/claims`, PUT `/attempts/{uuid}/lease`, GET
`/attempts/{uuid}/input`, PUT `/attempts/{uuid}/result` and `/failure`.
Scoped Spring SecurityFilterChain authenticates before MVC/body decoding, with
stateless ROLE_OCR_WORKER and per-worker AuthenticationProvider; Boot BOM manages
the security dependency. Unmatched public API keeps its existing policy, no global
CSRF disable/default login or generated user. Disabled→503, invalid/revoked
credential→401, forbidden worker/profile→403, unknown attempt→404, stale/conflict→409,
bytes→413, media/encoding→415, range→416, contract/provenance→422.
Safe ApiError only; no request contents/tokens in logs. Use direct TLS on private VPS
or trusted TLS proxy with backend bound to loopback. Non-loopback cleartext is
rejected. Do not expose these routes, trust arbitrary forwarded headers or log
Authorization/X-Lease-Token at proxies.

Per-worker Bearer secrets: 43–256 base64url characters, provision outside Git. Server
configuration stores SHA256 credential hashes and profile allowlists; worker_id in a
request body is not accepted. Rotate by adding hash, switching worker secret, then
removing old hash and redeploying. Duplicate hashes across workers fail validation.
Revocation also denies terminal replay. No authentication dependency in PDF children.

Claim body exactly `{"protocol":"cv-compass.ocr-job/1.0","profile_revision":"<revision>"}`.
Headers: canonical UUID Idempotency-Key + X-Lease-Token (32 random bytes, unpadded
base64url), generated/persisted before claim. 201 new /200 replay with Location,
204 empty. `ApiResponse.data` contains protocol, string job_id, attempt UUID, attempt_no,
server_time/lease_expires_at/hard_deadline_at, synthetic_only=true, size_bytes,
input_path and unchanged M6 ocr_request. Lease PUT accepts exactly `{}`.

Input checks job→attempt locks, proof and DB clock after locks without lease renewal.
Storage streaming is outside the transaction; opaque key only, no client paths,
filenames, redirects or compression. Content-Length/X-Content-SHA256 are supplied;
worker rechecks actual size/hash before OCR. Streaming checks elapsed time between
reads; blocking socket writes remain an HTTP server/proxy deployment concern, not a
claim of a hard filesystem I/O deadline. No transaction spans HTTP transfer/OCR.

The formerly referenced docs/POC_OCR_API_CONTRACT.md is absent in this checkout.
See the [contract source map](../../docs/contracts/README.md) and
[raw-text v2 extension](OCR_V2_RAW_TEXT.md) before changes; this historical v1
description is not a replacement contract approval. The M6 schema snapshot remains
preserved alongside v2.
Strict UTF8, duplicate/unknown keys, depth32, body caps and M6 semantics remain.
Digest is exact UTF8 HTTP bytes, not reserialized JSON. Deployment metadata is checked
against the immutable admission snapshot; preflight failures retain null observations
and empty models. These are authenticated worker declarations, not remote hardware
attestation. Delivered result is terminal (including OCR failed and cleanup warning).
201 receipt /200 exact replay even after expiry; different bytes or stale fence→409.
JSONB retains pages/text/raw_text/status/confidence/timings/engines/models/config.
Spring owns all DB writes. No rebind, overwrite or fabricated extraction.

Failure body: protocol + M7.2 OcrFailureCode, excluding server-only LEASE_EXPIRED.
Only DOWNLOAD_UNAVAILABLE/SUPERVISOR_TIMEOUT/LEASE_EXPIRED retry; hash/config/invalid
result do not. The configured scheduler calls existing expireDue, retaining job→attempt
lock order, retry budget/quarantine and hard deadline. Defaults remain 3 attempts,
120s lease/30s heartbeat. Technical tests cannot open production gate.

## M7.4: Ubuntu host supervisor

Run `python worker/run_pull_worker.py --config <absolute-operator.toml> [--once]`.
Standard-library HTTP, validated TLS, pinned origin, no redirects, bounded retries,
absolute socket deadline (including trickle responses). HTTPS DNS lookup still
depends on host resolver. Plain HTTP allows numeric loopback IPs only.

External TOML template (placeholders intentionally invalid; NOT measured pins):

```toml
enabled = false
synthetic_only = true
origin = "<configured-backend-origin>"
credential_env = "CV_COMPASS_OCR_TOKEN"
spool_root = "/private/ocr-synthetic-spool"
pipeline_config = "/private/m6-diagnostic.toml"
profile_revision = "<registered-revision>"
worker_commit = "<measured-40-hex-commit>"
image_id = "sha256:<measured-64-hex-image-id>"
config_fingerprint = "<measured-64-hex-M6-config-fingerprint>"
heartbeat_seconds = 30
http_timeout_seconds = 10
download_timeout_seconds = 60
retry_backoff_seconds = 1
poll_seconds = 5
max_http_attempts = 3
max_file_bytes = 10485760
max_result_bytes = 8388608
max_envelope_bytes = 8454144
max_spool_bytes = 67108864
```

Create private Linux spool outside Git, mode0700, owned by supervisor UID, distinct
from backend storage/inbox. Use existing M6 build_baseline.py/verify_baseline.py and
hash lockfiles; no laptop-only image. Require diagnostic Docker config + pinned
image ID/fingerprint; no native fallback or job-provided flags/config. The validated
operator TOML is snapshotted privately before CLI invocation. Credential environment
names must use CV_COMPASS_OCR_ prefix; LANG/PATH/HOME cannot be repurposed. Credentials stay in
supervisor; unchanged M6 safe child environment/mounts contain no token/socket.

Exclusive flock, one attempt at a time; persist claim UUID/token before HTTP. Stream
bounded PDF, verify size/SHA256, call existing M6 CLI in-process with heartbeat thread.
Monotonic lease budget is capped by original local hard deadline even if DB clock
rolls back. Lost heartbeat cancels through M6 owned-child kill/wait/remove lifecycle,
including cancellation during staging; never kill only Docker CLI. Pipeline timeout
must fit the remaining hard deadline with HTTP reserve before OCR begins.

Spool complete result envelope once, resend identical bytes. Crash/lost receipt replays
saved outcome only, never OCR again. Receipt validates job/attempt identities. Cleanup
warning submits the valid terminal result then halts intake, preserving locks/spool.
Uncertain outcome, stale lease, quota or cleanup errors retain private state and halt.
No automatic stale-lock deletion, foreign-resource cleanup, TTL purge or new intake
while uncertain. Operator owns retention/disk quotas. Configured spool budget limits
intake; active M6 temp budget remains separately enforced by existing M6 controls.

Recovery: stop supervisor; inspect its claim.json, owned UUID directory and M6 cleanup
ledger. Resolve **only owned** leftovers using M6 procedures. Remove halt.json only
after review; restarting replays submission.json/failure.json exactly. A persisted
CLEANUP_FAILED warning continues to halt even if its lock/ledger has disappeared.
After verifying the terminal receipt and resolving owned resources, manually archive
that owned UUID directory and its claim journal together; never alter result bytes
or erase an unacknowledged outcome to resume intake. Incomplete
directory without an outcome needs manual disposition, never automatic OCR rerun.
Never commit these private files (tokens/PDF/results) or expose them to PDF children.

## M7.5: synthetic enrollment and atomic enqueue

Both cv.ocr.enabled and cv.ocr.integration.enabled default false. Integration requires
non-prod profile, local storage, synthetic-storage-root matching configured storage,
namespace, nonempty workers/profiles and known generated fixture manifests.
Use external operator YAML, not a public enrollment endpoint:

```yaml
cv:
  ocr:
    enabled: false
    integration:
      enabled: false
      namespace: synthetic-fixtures-only
      synthetic-storage-root: /private/backend-synthetic-storage
      workers:
        worker_1:
          credential-sha256: ["<SHA256-of-worker-only-secret>"]
          profiles: ["<revision>"]
      profiles:
        "<revision>":
          worker-commit: "<measured-commit>"
          image-id: "sha256:<measured-image-id>"
          config-fingerprint: "<measured-M6-config-fingerprint>"
          engines-json: '<exact measured M6 engines JSON including model revisions/hashes>'
      fixtures:
        - fixture-id: generated-fixture-001
          sha256: "<measured-generated-PDF-hash>"
          size-bytes: 1234 # replace with actual measured size
          profile-revision: "<revision>"
```

Configure storage root and DB credentials externally under existing conventions.
Typed cv.ocr.integration properties expose reaper-delay/download-timeout/control-body
caps; application.yaml provides CV_OCR_* bindings. Registry has no permissive defaults.
Only known synthetic SHA + size in dedicated namespace enrolls; never filename/public
boolean/blanket storage scanning. Unknown PDF keeps ingestion semantics but no OCR job.

CvDocumentService invokes enqueue in the existing metadata transaction after single/
batch/disk persistence callback, before commit. MANDATORY propagation + saveAndFlush:
enqueue/commit failure rolls metadata/item/counter/history back and invokes existing
storage compensation. No after-commit gap. Admission snapshot binds generated ID +
fixture ID/hash/size/namespace + deployment manifest. Generation1 uniqueness/composite
FKs and legacy rows stay unchanged. Batch counters remain ingestion-only.

## Verification boundaries

Backend: JDK25, `cd backend; ./gradlew check --rerun-tasks`. Real PostgreSQL17 tests
use isolated schemas and private TEST_DB_URL/USERNAME/PASSWORD. OcrWorkerIntegrationTests
uses live HTTP + PG, **not** OCR-engine/resource-safety evidence.

Ubuntu: fresh venv, `pip install --require-hashes -r worker/requirements.lock -r
worker/requirements-test.lock`, `pip check`; provision Tesseract/models/font via M6.
Full `python worker/tests/run_checks.py --xml <new.xml>` requires host + Docker child.
`run_m7_independent_checks.py --xml <new.xml>` is explicitly a **subset**, not full gate.
compileall is syntax only. Transport stand-ins/unit fakes never prove PG/OCR integration.
Exact source fingerprint, commands/counts/logs are recorded in local M7 handoff;
old M6 80/80 or M7.2 results are not evidence for this source.

Docker is currently BLOCKED by the reported runtime problem; no restart, reconfigure,
privileged fallback or resource-bomb is authorized. Full Docker suite and real
backend→adapter→M6 child→JSONB flow are NOT VERIFIED until restored. No commit/push/
merge or M8 under this invocation. Baseline trial is not production-ready.
