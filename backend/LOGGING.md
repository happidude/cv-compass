# Operational logging

Backend uses the existing SLF4J/Logback stack and Spring Boot structured encoder;
worker uses Python logging. No collector, database table, migration or API/result
schema is added. `ApiError`, HTTP statuses, fencing, retries and result receipts
remain authoritative. Logs are diagnostic evidence, never a transaction ledger.

## Convention and context

Operational records have `timestamp` (ISO8601 with offset), `level`, `service`,
`event`, `message`, `errorCode` (`NONE` for normal events). Event/message are stable
names from the event enums. `documentId`, `jobId`, `attemptId`, `workerId` identify
background work; `extractionId`, `status`, page number/method/duration add detail.
Framework startup records retain their framework messages and may lack event IDs.

Reuse `OperationalLog` in backend modules: pass internal IDs and fixed reason codes,
never DTOs/entities/exceptions. The existing `CorrelationIdFilter` still accepts a
validated request header or creates a UUID, returns it in `X-Correlation-ID`, and
cleans MDC in finally. No request ID is persisted as a job ID. Backend events after
commit capture only the current correlation ID and explicit context; the callback
restores the previous MDC. Rollback emits no committed success. Outside an active
transaction a committed event is withheld and `LOG_TRANSACTION_UNAVAILABLE` is WARN.

Worker `observability.context(...)` uses ContextVar tokens restored in finally.
The heartbeat thread explicitly receives a copied context. PDF children receive no
request context, credentials or HTTP tokens: their validated page results are logged
in the supervisor. Worker HTTP exchanges generate a new correlation UUID for every
request, including retry, while preserving existing idempotency bytes/token. DEBUG
HTTP events and the matching backend request share that UUID; background IDs bridge
separate HTTP calls and processes. Set worker identity explicitly in operator config
as `CV_LOG_WORKER_ID`, matching the backend registry key; absent identity is reported
as `unconfigured`, never inferred from a credential.

INFO: stored document, enqueue/non-enqueue decision, claim, result receipt/storage,
state change, pipeline/page completion, valid server acknowledgement. WARN:
recoverable retry/expiry/heartbeat trouble. ERROR: terminal failure/halt/cleanup or
unavailable infrastructure operation. DEBUG: empty polls, healthy heartbeat and HTTP
exchange diagnostics. `OCR_RESULT_RECEIVED` means received/validated sufficiently to
identify the attempt, not committed; `OCR_EXTRACTION_STORED` and state success are
after commit. Worker `PIPELINE_FINISHED` is local processing status; only
`RESULT_ACKNOWLEDGED` follows a validated backend receipt.

Do not log CV content, rawText/text, original filenames, names/email/phone, payloads,
claims, authorization/lease tokens, credentials, environment, config or spool. Error
owners log a fixed reason and, where useful, exception class. Backend unexpected
request failures additionally carry bounded `applicationStack` source locations
from `SafeStackLocations`; no exception/cause/suppressed message is inspected.
Never interpolate an exception, cause/message or raw stack trace. Python formatter deliberately ignores
msg/args/exc_info outside the allowlisted event record. Diagnostic artifact/spool
access remains private; never send engine stderr or reports to a public log collector.
Retain existing warning/failure semantics; logging does not turn errors into success.

## Configuration

Defaults are console only and INFO. Do not create mandatory log files. Configuration
is outside Git and independent of OCR profile fingerprints.

| Setting | Backend | Worker pull supervisor |
|---|---|---|
| `CV_LOG_LEVEL` | INFO; standard Spring/Logback levels | INFO; DEBUG/INFO/WARN/WARNING/ERROR/CRITICAL |
| `CV_LOG_FORMAT` | unset = readable; `logstash` = JSON | unset = readable; `logstash` or `json` = JSON |
| Optional file | Spring `LOGGING_FILE_NAME` = operator path | `CV_LOG_FILE` = operator path |
| Size rotation | `CV_LOG_MAX_FILE_SIZE`, default 10MB | `CV_LOG_MAX_BYTES`, default 10485760 |
| Retention | `CV_LOG_MAX_HISTORY`, default 7 daily periods | `CV_LOG_BACKUP_COUNT`, default 7 rotated files |
| Total file cap | `CV_LOG_TOTAL_SIZE_CAP`, default 100MB | max bytes × (backup count + active file) |

Create/protect the configured log directory as the service account, restrict access,
and set a private umask (for example 077) for worker file logs. Retention models differ:
backend uses daily/size rolling; worker uses bounded size/count. Do not enable file
logging when the VPS supervisor collects stdout/stderr. No runtime address, port or
storage path is introduced by logging.

Example environment (do not commit actual paths):

```text
CV_LOG_LEVEL=INFO
CV_LOG_FORMAT=logstash
CV_LOG_WORKER_ID=<existing-registered-worker-key>
# Optional: LOGGING_FILE_NAME=<private-backend-log-path>
# Optional: CV_LOG_FILE=<private-worker-log-path>
```

Keep the offline CLI's machine-readable receipt/output/exit-code contract: it does
not initialize console logging. Pull initializes operational logging on stderr;
stdout and child IPC stay unchanged. Existing CLI receipts remain as before. If
embedding the CLI, do not globally reconfigure root logging or redirect stdout.

## Safe trace example (illustrative internal IDs, not runtime evidence)

```text
timestamp=2026-10-25T03:00:00.000Z level=INFO service=cv-compass-backend event=DOCUMENT_STORED correlationId=req-upload documentId=42 errorCode=NONE message=DOCUMENT_STORED
timestamp=2026-10-25T03:00:00.001Z level=INFO service=cv-compass-backend event=OCR_ENQUEUED correlationId=req-upload documentId=42 jobId=51 errorCode=NONE message=OCR_ENQUEUED
timestamp=2026-10-25T03:00:01.000Z level=INFO service=cv-compass-backend event=OCR_CLAIMED correlationId=req-claim documentId=42 jobId=51 attemptId=00000000-0000-4000-8000-000000000001 workerId=worker-a errorCode=NONE message=OCR_CLAIMED
timestamp=2026-10-25T03:00:01.010+00:00 level=INFO service=cv-compass-worker event=JOB_RECEIVED documentId=42 jobId=51 attemptId=00000000-0000-4000-8000-000000000001 workerId=worker-a errorCode=NONE message=JOB_RECEIVED
timestamp=2026-10-25T03:00:02.000+00:00 level=INFO service=cv-compass-worker event=PAGE_FINISHED documentId=42 jobId=51 pageNumber=1 method=native status=ok durationMs=83 errorCode=NONE message=PAGE_FINISHED
timestamp=2026-10-25T03:00:03.000Z level=INFO service=cv-compass-backend event=OCR_EXTRACTION_STORED correlationId=req-result documentId=42 jobId=51 extractionId=61 errorCode=NONE message=OCR_EXTRACTION_STORED
timestamp=2026-10-25T03:00:03.010+00:00 level=INFO service=cv-compass-worker event=RESULT_ACKNOWLEDGED documentId=42 jobId=51 attemptId=00000000-0000-4000-8000-000000000001 workerId=worker-a errorCode=NONE message=RESULT_ACKNOWLEDGED
```

Filter by documentId, then jobId/attemptId; request correlationIds intentionally
differ between upload/claim/result. Startup/reaper logs have no HTTP ID (`-` in text).

## Halt diagnostics and safe application locations

Worker failures are classified at the operation boundary. `WorkerFailure(Reason)`
is a ValueError subtype; external transport/file exceptions keep their original
type with immutable `Diagnostic` metadata. No exception-message parsing is used.
ERROR `WORKER_HALTED` includes `errorCode`, `exceptionType`, known task/worker IDs,
and `httpStatus`/failed request `correlationId` when available. A new HTTP UUID is
created before connection/TLS setup, retained even when no job is received. The
exception carries that small metadata across context cleanup; it carries no URL,
headers, credential or payload. Heartbeat errors keep their underlying diagnostic
when they cause lease cancellation. Error owners emit the halt once; other layers
re-raise the same error without logging a second stack trace.

| Boundary | Examples of stable reason codes |
|---|---|
| Logging startup | `LOG_CONFIGURATION_ERROR` (safe direct JSON stderr fallback if no handler exists) |
| Operator config | `CONFIG_READ_FAILED`, `CONFIG_PARSE_FAILED`, `PULL_CONFIGURATION`, `PIPELINE_CONFIG_INVALID`, `PIPELINE_REGISTRY` |
| Credential | `CREDENTIAL_MISSING`, `CREDENTIAL_INVALID` |
| HTTP transport | `CONNECTION_REFUSED`, `HTTP_TIMEOUT`, `TLS_FAILED`, `NETWORK_FAILED`, `HTTP_PROTOCOL_ERROR` |
| HTTP rejection | `HTTP_AUTHENTICATION_FAILED` (401), `HTTP_AUTHORIZATION_FAILED` (403), `HTTP_REJECTED` (other status) |
| Contract | `HTTP_BODY`, `HTTP_BODY_LIMIT`, `CLAIM_CONTRACT`, `CLAIM_IDENTITY`, `RECEIPT_IDENTITY` |
| Lease | `LEASE_CLOCK`, `LEASE_TIMING`, `LEASE_IDENTITY`, `LEASE_LOST`, `HEARTBEAT_NOT_REAPED` |
| Spool/cleanup | `PRIVATE_LINUX_SPOOL_REQUIRED`, `SPOOL_IO_FAILED`, `SPOOL_IDENTITY`, `SPOOL_LIMIT`, `CLEANUP_FAILED`, `CLEANUP_OWNERSHIP`, `HTTP_CLEANUP_FAILED` |

Explicit independent spool/lease reasons take precedence over a coincident HTTP
deadline. Diagnostic metadata never changes existing retry conditions, sleeps,
idempotency bytes, fencing, acknowledgement, retained spool or exit statuses.
The marker format/lifecycle is unchanged: an existing marker contains no trustworthy
historical cause, so stopping on it reports `OPERATOR_RECOVERY_REQUIRED`. Never infer
a historical network/credential error from it or automatically clear it. An
unclassified exception reports `UNEXPECTED_WORKER_FAILURE`, not a guessed cause.

Backend `SafeStackLocations.capture(Throwable)` examines at most 64 frames per
throwable, at most 3 throwables including the root, and emits at most 12 application
frames/2048 characters. Only `com.cvcompass.backend.*` class, method, safe file name
and line survive. Non-application frames and suppressed exceptions are not emitted;
an identity set terminates cause cycles. Unsafe/absent file names become
`UnknownSource`; unavailable metadata is omitted safely. The result may be empty
if no application frame is found within the inspection budget.
`OperationalLog` accepts this field only as the private-constructor typed capture
result, never an arbitrary string. Text pattern and JSON MDC include the same
field; MDC is restored afterward. `GlobalExceptionHandler` remains the single
owner for unexpected HTTP failures, retaining exceptionType/correlationId and the
unchanged safe ApiError response, with no stack field sent to clients.

Illustrative records below contain synthetic IDs/locations, not runtime evidence:

```json
{"timestamp":"2026-10-25T03:00:00+00:00","level":"ERROR","service":"cv-compass-worker","event":"WORKER_HALTED","message":"WORKER_HALTED","errorCode":"CONNECTION_REFUSED","exceptionType":"ConnectionRefusedError","workerId":"worker-a","correlationId":"00000000-0000-4000-8000-000000000001"}
{"timestamp":"2026-10-25T03:00:01+00:00","level":"ERROR","service":"cv-compass-worker","event":"WORKER_HALTED","message":"WORKER_HALTED","errorCode":"HTTP_AUTHENTICATION_FAILED","exceptionType":"HttpFailure","httpStatus":401,"workerId":"worker-a","correlationId":"00000000-0000-4000-8000-000000000002"}
{"timestamp":"2026-10-25T03:00:02+00:00","level":"ERROR","service":"cv-compass-worker","event":"WORKER_HALTED","message":"WORKER_HALTED","errorCode":"CLAIM_CONTRACT","exceptionType":"WorkerFailure","workerId":"worker-a","documentId":"42","jobId":"51","attemptId":"00000000-0000-4000-8000-000000000003"}
{"timestamp":"2026-10-25T03:00:03Z","level":"ERROR","service":"cv-compass-backend","event":"HTTP_REQUEST_FAILED","message":"HTTP_REQUEST_FAILED exceptionType=java.lang.IllegalStateException","errorCode":"INTERNAL_ERROR","exceptionType":"java.lang.IllegalStateException","correlationId":"request-example","applicationStack":"com.cvcompass.backend.ExampleService.save(ExampleService.java:42)"}
```

## Restart and provenance

Logging and extraction changes in `worker/src` require a new source-built child
image. Never point new source at an old image ID or invent a worker commit. For an
existing immutable baseline, on the provisioned Linux/WSL Python 3.14.8 runtime:

```sh
<python> worker/spikes/m6_2_hardening/rebuild_worker.py \
  --baseline <existing-worker-build-directory> --output <new-worker-build-directory>
<python> worker/spikes/m6_2_hardening/verify_baseline.py --build <new-worker-build-directory>
# Also run cleanup-only on that same output if verification is interrupted:
<python> worker/spikes/m6_2_hardening/verify_baseline.py --build <new-worker-build-directory> --cleanup-only
```

Output must be a fresh directory below this checkout's worker/build. Rebuild retains
the inspected immutable runtime/dependency images and hash-locked wheels, rebuilds
source, records exact Git commit/dirty state and source manifest, and measures image
ID. Full verifier installs a fresh hash-locked venv and owns cleanup resources.
Use `build_baseline.py` for fresh provisioning; no dependency/engine pin is relaxed.

After verified source is committed, bind the real worker commit and measured image
to a new profile revision. Update `pipeline.toml` `docker_image`, measure the new
`Config.fingerprint()`, then copy the exact fingerprint/image/commit/engine metadata
to private pull config and backend registry. Existing queued jobs retain immutable
old snapshots; drain/disposition them under the existing recovery procedure before
switching the worker. Do not rewrite existing extractions or admission snapshots.

Stop intake, drain active work and acknowledge pending outcomes; preserve uncertain
spool/halts. Rebuild backend (`gradlew bootJar`), restart it with existing private
database/storage/registry config, then restart pull with its existing `--config` path
and new logging environment. See `LOCAL_OCR.md` for operator-supplied commands and
endpoints. No runtime registry was updated or service restarted by this task when
live E2E was waived. GCC/Tesseract production gate remains BLOCKED.
