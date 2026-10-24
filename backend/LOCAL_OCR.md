# Local UI → OCR flow

This is an explicit local diagnostic mode. The default remains synthetic-only;
production is still blocked by the unresolved GCC/Tesseract gate, and OCR accuracy
has not been evaluated. There is no candidate/field mapping or backfill.

Executed final checks and live native/scanned E2E evidence:
[LOCAL_OCR_VERIFICATION.md](LOCAL_OCR_VERIFICATION.md).

## Why uploaded documents had no OCR job

M7 required an enrolled SHA256 **and** byte length for every input. An unknown PDF
was stored normally, without a job. Both OCR switches, a valid deployment registry,
authenticated worker/profile and the storage namespace are also required. Upload
success is independent of OCR success. M9 already polls document detail and renders
stored page text, so that UI/pipeline is reused.

## Configuration

Keep credentials in the existing private `.env.properties`/environment. Never put
worker credentials in `VITE_*`, Git, terminal output or an API request from the UI.
Use the existing database and storage; do not create a second demo backend. The
deployment registry below is an **external** YAML file. Replace placeholders with
measured image/profile evidence; arbitrary hashes or the current HEAD are not pins.

| Setting | Required / default | Where |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Existing PostgreSQL database/credentials | Backend private `.env.properties` |
| `CV_STORAGE_PROVIDER`, `CV_STORAGE_ROOT` | `local`, existing absolute storage root | Backend private config |
| `server.address`, `server.port` | Operator's existing bind address/port; no port in source | External backend YAML or Spring `SERVER_ADDRESS`/`SERVER_PORT` environment |
| `cv.ocr.enabled`, `cv.ocr.integration.enabled` | Both `true` to enqueue/serve worker | External YAML / existing `CV_OCR_*` variables |
| `cv.ocr.integration.admission.mode` | `synthetic-only` default; explicit `local` opt-in | YAML / `CV_OCR_ADMISSION_MODE` |
| `cv.ocr.integration.admission.local-profile-revision` | Required only in local mode, registered and worker-authorized | YAML / `CV_OCR_LOCAL_PROFILE_REVISION` |
| `cv.ocr.integration.namespace` | Required deployment namespace | External YAML; legacy `CV_OCR_SYNTHETIC_NAMESPACE` remains compatible |
| `cv.ocr.integration.synthetic-storage-root` | Required/matching absolute root in synthetic-only mode; not required in local mode | Existing config name retained |
| `workers`, `profiles` | Required in both modes; credential SHA256 and measured provenance | External YAML |
| `fixtures` | Required in synthetic-only; not needed for local | External YAML |
| `CV_IMPORT_ENABLED` | Leave `false`; do not ingest old disk data | Backend config |
| `origin` | Exact backend origin, no path/userinfo; HTTP numeric loopback only, otherwise HTTPS | Private worker `pull.toml` |
| `synthetic_only`, `local_only` | Defaults `true`, `false`; for local explicitly `false`, `true` | `pull.toml`; exactly one must be true |
| `credential_env` | Existing `CV_COMPASS_OCR_*` environment name | `pull.toml`; secret only in supervisor environment |
| `pipeline_config`, `spool_root` | Existing absolute Linux paths; spool owned/private mode 0700 | `pull.toml` |
| `API_PROXY_TARGET` | Required for live Vite development; same backend origin | Frontend server environment / `.env.local` |
| `VITE_MOCK` | `false` | Frontend `.env.local` |
| `VITE_API_BASE_URL` | `/api` for the Vite proxy | Frontend `.env.local` |
| `VITE_POLL_INTERVAL_MS`, `VITE_POLL_MAX_MS` | Existing validated polling interval/budget; set the budget for expected OCR duration | Frontend `.env.local` |
| UI port | Operator-selected CLI `--port`; browser tests use dynamic ports | Frontend terminal |
| `LOCAL_E2E_UI_ORIGIN` | Existing UI origin, required for live E2E | Test terminal only |
| `LOCAL_E2E_TIMEOUT_MS` | Optional, test budget defaults to 180000 ms | Test terminal only |

External backend YAML (all `<...>` are placeholders, not runnable values):

```yaml
server:
  address: ${SERVER_ADDRESS}
  port: ${SERVER_PORT}
cv:
  ocr:
    enabled: true
    integration:
      enabled: true
      namespace: local-cv-compass
      admission:
        mode: local
        local-profile-revision: <registered-profile>
      workers:
        local-worker:
          credential-sha256: ["<SHA256-of-private-worker-token>"]
          profiles: ["<registered-profile>"]
      profiles:
        "<registered-profile>":
          worker-commit: "<source-commit-of-pinned-OCR-image>"
          image-id: "sha256:<measured-image-ID>"
          config-fingerprint: "<measured-pipeline-fingerprint>"
          engines-json: '<exact measured engines/model JSON>'
```

Changing policy never fabricates fixture identity: local admission snapshots have
`admission_mode=local` and no `fixture_id`. Claims have `synthetic_only=false` and
`local_only=true`. Legacy synthetic claims remain compatible. The worker rejects a
claim from the other mode before download. Auth, immutable image/profile provenance,
transactional enqueue/compensation, lease/heartbeat/fencing/retry and Docker isolation
are unchanged. Every new successful ingestion uses the same admission policy; batch
and importer do not bypass it. Identical PDFs uploaded in separate requests remain
separate ingestions; the policy is not content deduplication.

The existing private `pull.toml` retains all timing/size settings and measured pins:

```toml
enabled = true
synthetic_only = false
local_only = true
origin = "<same-origin-as-backend-and-API_PROXY_TARGET>"
credential_env = "CV_COMPASS_OCR_TOKEN"
spool_root = "<existing-private-Linux-spool>"
pipeline_config = "<absolute-existing-pipeline.toml>"
profile_revision = "<registered-profile>"
worker_commit = "<source-commit-of-pinned-OCR-image>"
image_id = "sha256:<measured-image-ID>"
config_fingerprint = "<measured-pipeline-fingerprint>"
```

Keep the pipeline in `mode="diagnostic"`, `adapter="docker"`, with the pinned image
and measured Tesseract/models paths. Local mode does not open production mode or
replace isolation with native fallback. Host supervisor source and the pinned OCR
child image are separate deployment artifacts: report both honestly. This change
reused the provisioned child image and models; it did not claim a rebuilt image.

```text
Windows host
  <checkout>/backend/.env.properties              private database/storage config
  <private-backend-config>/backend-local.yaml      registry and bind config
  <existing-storage>/                             originals; never bulk-clean
  <existing-inbox>/processed/ and failed/           importer remains disabled
  <checkout>/frontend/.env.local                   public VITE config + server proxy
Linux/WSL host
  <private-worker>/pull.toml, pipeline.toml, token  operator-owned, outside Git
  <existing-spool>/claim.json                      private replay identity/token
  <existing-spool>/<claim-UUID>/                   input/outcome/cleanup ownership
  <existing-tool-output>/verification/models/      host preflight assets
Docker child
  /models, /usr/local/bin/tesseract                 pinned image assets
  bounded owned input volume/workspace             no host CV path/token/socket
```

## Start the three processes

Verified runtimes: Windows Microsoft JDK25.0.4.1 and Node24.22.0/npm11.19.1; worker
on Ubuntu24.04 WSL with CPython3.14.8 and Docker29.6.1. PostgreSQL17.11 is reused.
Start PostgreSQL/Docker as normally provisioned, then backend, worker and frontend.
The worker HTTP loopback origin must actually reach the backend from its OS; do not
replace it with cleartext non-loopback HTTP or disable origin validation.

Backend PowerShell, from the source checkout's `backend/`, with the existing private
database/storage config imported explicitly (the existing file can stay in its
runtime directory). Use the `feature/local-ocr-flow` checkout until it is merged:

```powershell
$env:JAVA_HOME = '<JDK25-home>'
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
# Set SERVER_ADDRESS/SERVER_PORT to your existing bind configuration if the YAML uses them.
$env:CV_OCR_DEPLOYMENT_CONFIG = '<absolute-external-backend-local.yaml>'
$env:CV_BACKEND_PRIVATE_CONFIG = '<absolute-existing-.env.properties>'
./gradlew.bat bootJar
$jar = (Get-ChildItem build/libs/*.jar | Where-Object Name -NotLike '*-plain.jar').FullName
New-Item -ItemType Directory -Force build/tmp/jvm | Out-Null
$socketTmp = (Resolve-Path build/tmp/jvm).Path
java -Duser.timezone=UTC "-Djdk.net.unixdomain.tmpdir=$socketTmp" -jar $jar `
  "--spring.config.import=file:$env:CV_BACKEND_PRIVATE_CONFIG" `
  "--spring.config.additional-location=file:$env:CV_OCR_DEPLOYMENT_CONFIG" --cv.import.enabled=false
```

On this Windows/JDK installation the short socket tmpdir avoids a JDK loopback pipe
startup error. UTC avoids PostgreSQL rejecting the host's `Asia/Saigon` JVM alias;
the UI keeps its separately configured display timezone.

Worker in the existing Ubuntu/WSL environment:

```bash
export CV_COMPASS_OCR_TOKEN="$(cat '<private-token-file>')"
<provisioned-python> <checkout>/worker/run_pull_worker.py --config '<existing-pull.toml>'
```

The token is not a CLI argument and is excluded from PDF child environments. Stop
here if configuration/health/auth fails; inspect safe error codes, never print the
environment/token or dump spool outcomes.

Frontend PowerShell, from `frontend/` (keep `VITE_MOCK=false` in `.env.local`):

```powershell
npm ci
$env:API_PROXY_TARGET = '<existing-backend-origin>'
npm run dev -- --port <your-UI-port>
```

Open the UI origin printed by Vite, upload a PDF, then choose **Mở CV**. Detail polls
PENDING/PROCESSING and displays persisted page text/rawText and partial/failed states.
No fixture registration is required in local mode. GET `/api/documents/{id}` returns
the processing job and OCR pages; upload itself still returns metadata only.
Polling remains bounded; if a long job outlives the configured UI budget, use the
visible manual refresh action or increase VITE_POLL_MAX_MS within its validated cap.

For fresh provisioning, use the existing tooling on Ubuntu; do not edit Docker or
invent image/model versions:

```bash
<provisioned-python> worker/spikes/m6_2_hardening/build_baseline.py --output '<fresh-build-directory>'
<provisioned-python> worker/spikes/m6_2_hardening/verify_baseline.py --build '<fresh-build-directory>'
```

These tools use hash-locked dependencies/models, measured image IDs and owned Docker
resources. Copy measured profile evidence into the private registry/pipeline. A build
or technical PASS does not remove the upstream GCC/Tesseract production blocker.

## Recovery, tests and safe stop

Never delete `halt.json`, `claim.json` or spool contents to restart. Stop intake,
inspect only owned claim/job metadata and cleanup ledgers, and verify the attempt's
server state. Retained submission/failure bytes must be replayed exactly by the
existing supervisor after review; never rerun OCR or modify an unacknowledged result.
An incomplete attempt or cleanup warning needs operator disposition under
`worker/design/M7_INTEGRATION.md`. Archive a journal/halt pair only after proving
there is no DB attempt/outcome, or after verifying its terminal receipt and cleanup.
The original halt marker retains only a generic code, so its historical exception
cannot be reconstructed from that marker alone.

For this local recovery the retained claim had **zero DB attempts**, no UUID attempt
directory or outcome, and no running supervisor. Its journal/halt pair was archived
together under the existing spool while holding the exclusive pull lock. They were
not blindly deleted. The observed worker/backend origin mismatch was corrected from
existing bind configuration; database host was corrected only after user approval.

Run full backend `./gradlew.bat check --rerun-tasks` with private `TEST_DB_*` values;
tests own and remove temporary schemas/storage, never reset runtime tables. Run
`npm run check` and `npm run test:browser`. Browser tests allocate dynamic ports.
For live E2E, first run the three processes above, then:

```powershell
$env:LOCAL_E2E_UI_ORIGIN = '<running-UI-origin>'
npm exec playwright -- test --config playwright.local.config.ts
```

This test generates a unique synthetic PDF, uploads through the UI and checks job
creation, persisted PAGE_RESULT text and live UI rendering. It never starts a new
backend/database/storage, assumes no empty runtime database, or opens an existing CV.
Synthetic-only rejection is checked independently with real PostgreSQL integration
tests. Do not run the older isolated smoke against runtime data.

To stop, cease uploads, wait for active work/outcome delivery, then interrupt the
worker and stop frontend/backend. Check only resources labeled/recorded as owned by
the run. Keep uncertain journals/results and originals. Do not bulk-clean storage,
reset public tables or remove resources belonging to another run.
