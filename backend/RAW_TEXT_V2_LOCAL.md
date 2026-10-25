# OCR rawText v2: local deployment

## Contract and old data

`cv-compass.ocr/2.0` is a breaking result/request version. Each page carries
`raw_text` and has no `text` field. Native pages use the initial
`pdfplumber.extract_text(layout=False)` result; OCR pages use Tesseract's `.txt`
result. Neither is re-ordered, dehyphenated, normalized or silently truncated.
This preserves the source library output, but does not prove its reading order.
The 50,000 Unicode code point page ceiling, 500,000 total code point ceiling,
8 MiB result ceiling and existing status/exit-code rules still apply.

The backend accepts both v1 and v2 submissions. Admission snapshots store
`result_version`; snapshots created before this field existed default to v1.
Claim requests use the version from the immutable snapshot. An acknowledged
v1 outcome can be replayed with its original bytes. Stored v1 JSONB remains
unchanged: the query API reads `raw_text` and ignores `text`. Missing `raw_text`,
unknown versions or malformed stored page data return the existing HTTP 503
availability error; the API never falls back to `text`. No migration or backfill
is required. The UI renders `rawText` as escaped text inside `<pre>` and counts
Unicode code points.

## Switch profile safely

Use a **new profile revision** and keep the old image/profile/worker registration
while any old job may be claimed or exact outcome may need acknowledgement.
Read-only inspection on 2026-10-25 found 12 COMPLETED and 1 FAILED jobs, no
PENDING/PROCESSING jobs, and 13 ACCEPTED attempts in the existing local DB.
The private spool was not located, so this does not establish that every saved
outcome was acknowledged. Inspect the actual private spool for outcome files and
recover or drain them with the old pinned profile. Never edit or delete saved
bytes, admission snapshots or extractions to switch versions.

Create a new private backend profile entry and worker configuration using the
measured values below. Placeholders are intentionally not deployment pins:

```yaml
cv:
  ocr:
    integration:
      admission:
        local-profile-revision: <new-v2-profile-revision>
      workers:
        <existing-worker-id>:
          profiles: [<old-profile-revision>, <new-v2-profile-revision>]
      profiles:
        <new-v2-profile-revision>:
          result-version: cv-compass.ocr/2.0
          worker-commit: <commit-containing-final-worker-source>
          image-id: sha256:<measured-rebuilt-image-id>
          config-fingerprint: <recomputed-pipeline-fingerprint>
          engines-json: '<measured-engine-metadata-json>'
```

Keep the existing credential hash, namespace, storage and other registry values.
Do not replace a profile revision already snapshotted by a job. The private
`pull.toml` must select the new revision and measured `worker_commit`, `image_id`
and `config_fingerprint`; its `pipeline_config` must point to a pipeline TOML
whose `docker_image` is that same rebuilt ID. Recompute the fingerprint from the
final TOML using `Config.load(Path(...)).fingerprint()` in the provisioned worker
Python environment. Never copy an old fingerprint after changing the image ID.

## Build and restart

From the final committed checkout, with the existing provisioned Docker baseline:

```sh
cd <checkout>/worker
<provisioned-python> spikes/m6_2_hardening/rebuild_worker.py \
  --baseline <existing-baseline-build> --output build/<fresh-v2-build-name>
<provisioned-python> spikes/m6_2_hardening/verify_baseline.py \
  --build build/<fresh-v2-build-name>
```

`images.json` gives the measured image ID; `source-version.json` records the
commit and dirty flag. The image source manifest must match the final worker
source. This build does not open the production GCC/Tesseract gate.

Build backend with `./gradlew.bat bootJar` on Windows (or `./gradlew bootJar` on
Linux) and frontend with `npm ci && npm run build` in their directories. Use the
existing private DB/storage configuration and the external backend registry YAML.
Start/restart the existing backend, then the worker pointing at the new private
`pull.toml`, then the frontend. The exact backend origin, UI port, token file,
storage and spool paths come from the existing private configuration; examples in
`LOCAL_OCR.md` show the commands without hardcoded endpoints. The user can then
upload a **new** PDF through the UI. No old document is reprocessed automatically.

Full test suites and live end-to-end upload were deferred for this change. Do not
report full quality gate or production readiness as passed. The GCC/Tesseract
production gate remains blocked.
