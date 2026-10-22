param(
    [string]$WorkerRoot = (Resolve-Path "$PSScriptRoot/../..").Path,
    [string]$Image = 'sha256:6b5d23e3d54c6dd0a6223a2e6c0839bedc9041255a470ef42ff2de14088cd749'
)
$ErrorActionPreference = 'Stop'
$out = Join-Path $WorkerRoot 'build/m6-2/ubuntu'
$wheel = Join-Path $WorkerRoot 'build/m6-2/pypdf-6.21.1-py3-none-any.whl'
$font = Join-Path $WorkerRoot 'build/m6-1-spike/dejavu-fonts-ttf-2.37/ttf/DejaVuSans.ttf'
foreach ($path in @($wheel, $font)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Missing synthetic test prerequisite' }
}
New-Item -ItemType Directory -Force $out | Out-Null
$name = 'cv-compass-m62-final-check'
if (docker ps -a --filter "name=^/$name`$" --format '{{.ID}}') { throw 'Owned test container already exists; inspect before reuse' }
$commands = 'python3 -m pip install --no-index --no-cache-dir --target /tmp/test-deps /pypdf-6.21.1-py3-none-any.whl && python3 -W error /worker/tests/run_checks.py --xml /report/tests.xml'
docker create --name $name --rm --network none --read-only --memory 1g --memory-swap 1g --cpus 2 --pids-limit 64 --cap-drop ALL --security-opt no-new-privileges --tmpfs /tmp:rw,noexec,nosuid,size=256m --env PYTHONPATH=/worker/src:/tmp/test-deps --env OCR_TEST_TESSERACT=/usr/local/bin/tesseract --env OCR_TEST_MODELS=/models --env OCR_TEST_FONT=/fixture-font.ttf --env OCR_TEST_REPORT_DIR=/report --mount "type=bind,source=$WorkerRoot,target=/worker,readonly" --mount "type=bind,source=$wheel,target=/pypdf-6.21.1-py3-none-any.whl,readonly" --mount "type=bind,source=$out,target=/report" --mount "type=bind,source=$font,target=/fixture-font.ttf,readonly" $Image sh -c $commands
if ($LASTEXITCODE -ne 0) { throw 'Container create failed' }
$container = (docker inspect $name | ConvertFrom-Json)[0]
@{
    image=$container.Image; memory=$container.HostConfig.Memory
    memory_swap=$container.HostConfig.MemorySwap; nano_cpus=$container.HostConfig.NanoCpus
    pids_limit=$container.HostConfig.PidsLimit; network=$container.HostConfig.NetworkMode
    readonly_rootfs=$container.HostConfig.ReadonlyRootfs; user=$container.Config.User
    tmpfs=$container.HostConfig.Tmpfs; cap_drop=$container.HostConfig.CapDrop
    security_opt=$container.HostConfig.SecurityOpt
} | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $out 'docker-applied-settings.json') -Encoding utf8
$started = [DateTime]::UtcNow
docker start --attach $name 2>&1 | Tee-Object (Join-Path $out 'full-check.log')
$checkExit = $LASTEXITCODE
if ($checkExit -ne 0) { throw "Python check failed: $checkExit" }
$xml = Get-Item (Join-Path $out 'tests.xml')
if ($xml.LastWriteTimeUtc -lt $started) { throw 'JUnit XML is stale' }
Write-Output 'Fresh Ubuntu Python check and JUnit XML completed.'
