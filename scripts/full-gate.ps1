<#
.SYNOPSIS
  Full local gate on the current checkout's HEAD: backend, frontend and worker (docs/VERIFICATION.md).

.DESCRIPTION
  Usage: scripts\full-gate.ps1 [-JavaHome <jdk-dir>]

  -JavaHome defaults to $env:JAVA_HOME, then to the developer JDK 25 below. It is passed only to
  the backend Gradle child process; the caller's and the system environment stay unchanged.
  TEST_DB_URL/TEST_DB_USERNAME/TEST_DB_PASSWORD are read only from the environment; only their
  presence is checked and their values are never printed or written by this script.

  Logs go to build\gate\<sha>\<timestamp>\ of this checkout (one fresh folder per run, never
  overwritten). Prints at most 15 lines. Exit code is non-zero when any part fails or is blocked.

  Worker runs inside WSL distro -WslDistro (default Ubuntu-24.04, whose CPython 3.14.8 lives in the
  user's ~/.local/bin, so the worker script runs under a login shell) from a script-owned clone
  (~/cv-compass-gate) detached at the same SHA, with a fresh worker\build\gate-<sha>-<timestamp>
  output each run. Any python3.14 other than exactly 3.14.8 is BLOCKED with the version found.
  Cleanup-only always runs after verify, even when verify fails.
#>
[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$WslDistro = 'Ubuntu-24.04'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$prevConsoleEncoding = [Console]::OutputEncoding
[Console]::OutputEncoding = $utf8

$ExpectedPython = 'Python 3.14.8'
$WslGateClone = '~/cv-compass-gate'
$DefaultJavaHome = 'C:\Users\NguyenVanHoang\.jdks\ms-25.0.4.1'
$TestDbVars = @('TEST_DB_URL', 'TEST_DB_USERNAME', 'TEST_DB_PASSWORD')
if ([string]::IsNullOrWhiteSpace($JavaHome)) { $JavaHome = $DefaultJavaHome }
$JavaHome = $JavaHome.Trim().TrimEnd('\', '/')

$root = (& git -C $PSScriptRoot rev-parse --show-toplevel)
if ($LASTEXITCODE -ne 0) { Write-Output 'full-gate: not inside a git repository'; exit 1 }
$root = [System.IO.Path]::GetFullPath($root.Trim())
$sha = (& git -C $root rev-parse HEAD).Trim()
$short = $sha.Substring(0, 12)
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logDir = Join-Path $root "build\gate\$sha\$stamp"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
# Untracked (not ignored) files also reach gradle/npm, so they make the evidence differ from HEAD.
$dirty = @(& git -C $root status --porcelain=v1 -unormal).Count -gt 0

$results = New-Object System.Collections.Generic.List[object]

function Add-Result([string]$name, [string]$status, [string]$detail, [datetime]$start) {
    $secs = [int]((Get-Date) - $start).TotalSeconds
    $results.Add([pscustomobject]@{ Name = $name; Status = $status; Detail = $detail; Secs = $secs })
}

# cmd.exe redirects raw bytes, so native UTF-8 output reaches the log unchanged and
# Windows PowerShell never turns stderr lines into terminating errors.
function Invoke-Logged([string]$workDir, [string]$commandLine, [string]$log) {
    Push-Location -LiteralPath $workDir
    try {
        & cmd.exe /d /c "$commandLine > `"$log`" 2>&1"
        return $LASTEXITCODE
    } finally {
        Pop-Location
    }
}

function Get-JUnitSummary([string]$dir) {
    $files = @(Get-ChildItem -LiteralPath $dir -Filter '*.xml' -File -ErrorAction SilentlyContinue)
    if ($files.Count -eq 0) { return 'no JUnit XML' }
    $t = 0; $f = 0; $e = 0; $s = 0
    foreach ($x in $files) {
        [xml]$doc = [System.IO.File]::ReadAllText($x.FullName, $utf8)
        foreach ($suite in @($doc.SelectNodes('//testsuite'))) {
            $t += [int]$suite.tests; $f += [int]$suite.failures; $e += [int]$suite.errors; $s += [int]$suite.skipped
        }
    }
    return "tests=$t failures=$f errors=$e skipped=$s"
}

# --- Backend -------------------------------------------------------------------------------
$start = Get-Date
$backend = Join-Path $root 'backend'
# Only presence is checked; values stay in the environment and are never read into output or logs.
$missingDb = @($TestDbVars | Where-Object { [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($_, 'Process')) })
if ($missingDb.Count -gt 0) {
    Add-Result 'backend' 'BLOCKED' "not set in this shell: $($missingDb -join ', ')" $start
} elseif (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe') -PathType Leaf)) {
    Add-Result 'backend' 'BLOCKED' 'JDK not found at -JavaHome (bin\java.exe missing)' $start
} else {
    # `set` inside cmd.exe scopes JAVA_HOME to the Gradle child only.
    $gradle = "set `"JAVA_HOME=$JavaHome`" && .\gradlew.bat check --rerun-tasks --console=plain"
    $code = Invoke-Logged $backend $gradle (Join-Path $logDir 'backend-check.log')
    $junit = Get-JUnitSummary (Join-Path $backend 'build\test-results\test')
    $jdk = Split-Path $JavaHome -Leaf
    Add-Result 'backend' $(if ($code -eq 0) { 'PASS' } else { 'FAIL' }) "gradlew check --rerun-tasks exit=$code jdk=$jdk; $junit" $start
}

# --- Frontend ------------------------------------------------------------------------------
$frontend = Join-Path $root 'frontend'
$start = Get-Date
if (-not (Test-Path (Join-Path $frontend 'node_modules'))) {
    Add-Result 'frontend' 'BLOCKED' 'node_modules missing; run npm ci in frontend' $start
} else {
    $code = Invoke-Logged $frontend 'npm run check' (Join-Path $logDir 'frontend-check.log')
    Add-Result 'frontend-check' $(if ($code -eq 0) { 'PASS' } else { 'FAIL' }) "npm run check exit=$code" $start
    $start = Get-Date
    $browserLog = Join-Path $logDir 'frontend-browser.log'
    $code = Invoke-Logged $frontend 'npm run test:browser' $browserLog
    $status = if ($code -eq 0) { 'PASS' } else { 'FAIL' }
    $detail = "npm run test:browser exit=$code"
    # A missing Playwright browser binary is an environment prerequisite, not a test failure.
    if ($code -ne 0 -and (Select-String -LiteralPath $browserLog -SimpleMatch "Executable doesn't exist" -Quiet)) {
        $status = 'BLOCKED'
        $detail = 'Playwright browser not installed (npx playwright install)'
    }
    Add-Result 'frontend-browser' $status $detail $start
}

# --- Worker (WSL) --------------------------------------------------------------------------
$start = Get-Date
$repoWin = (Split-Path ([System.IO.Path]::GetFullPath((& git -C $root rev-parse --path-format=absolute --git-common-dir).Trim())) -Parent)
$workerScript = Join-Path $logDir 'worker-gate.sh'
$workerLog = Join-Path $logDir 'worker.log'
# Script-owned clone: never touches the developer's WSL checkout. Fetch is local (no network);
# build/verify still need Docker, and the image build downloads pinned artifacts by design.
$sh = @"
set -u
umask 022
SRC=`$(wslpath -a '$($repoWin -replace '\\', '/')')
GATE=$WslGateClone
BUILD=worker/build/gate-$short-$stamp
docker info --format 'docker {{.OSType}} cgroup{{.CgroupVersion}}' || { echo 'BLOCKED: docker unavailable'; exit 20; }
command -v python3.14 >/dev/null || { echo 'BLOCKED: python3.14 missing'; exit 20; }
PYVER=`$(python3.14 --version 2>&1)
[ "`$PYVER" = '$ExpectedPython' ] || { echo "BLOCKED: python3.14 is '`$PYVER', need '$ExpectedPython'"; exit 20; }
if [ ! -d "`$GATE/.git" ]; then git -c safe.directory='*' clone --quiet --no-checkout "`$SRC" "`$GATE" || exit 21; fi
cd "`$GATE" || exit 21
git -c safe.directory='*' fetch --quiet "`$SRC" '+refs/heads/*:refs/remotes/local/*' || exit 21
git checkout --quiet --detach $sha || exit 21
test "`$(git rev-parse HEAD)" = '$sha' || exit 21
echo "source `$(git rev-parse HEAD) build=`$BUILD"
python3.14 worker/spikes/m6_2_hardening/build_baseline.py --output "`$BUILD"; b=`$?
v=1
if [ `$b -eq 0 ]; then python3.14 worker/spikes/m6_2_hardening/verify_baseline.py --build "`$BUILD"; v=`$?; fi
python3.14 worker/spikes/m6_2_hardening/verify_baseline.py --cleanup-only --build "`$BUILD"; c=`$?
echo "RESULT build=`$b verify=`$v cleanup=`$c"
[ `$b -eq 0 ] && [ `$v -eq 0 ] && [ `$c -eq 0 ]
"@
[System.IO.File]::WriteAllText($workerScript, ($sh -replace "`r`n", "`n"), $utf8)
$wslScript = $null
if (Get-Command wsl.exe -CommandType Application -ErrorAction SilentlyContinue) {
    $wslScript = (& wsl.exe -d $WslDistro -- wslpath -a ($workerScript -replace '\\', '/'))
    if ($LASTEXITCODE -ne 0) { $wslScript = $null }
}
if (-not $wslScript) {
    Add-Result 'worker' 'BLOCKED' "WSL distro $WslDistro unavailable" $start
} else {
    $code = Invoke-Logged $root "wsl.exe -d $WslDistro -- bash -l `"$($wslScript.Trim())`"" $workerLog
    $summary = @([System.IO.File]::ReadAllLines($workerLog, $utf8) | Where-Object { $_ -match '^(RESULT|BLOCKED)' } | Select-Object -Last 1)
    $detail = if ($summary.Count) { $summary[0] } else { "exit=$code" }
    $status = if ($code -eq 0) { 'PASS' } elseif ($code -eq 20 -or $code -eq 21) { 'BLOCKED' } else { 'FAIL' }
    Add-Result 'worker' $status $detail $start
}

# --- Summary -------------------------------------------------------------------------------
$failed = @($results | Where-Object { $_.Status -ne 'PASS' }).Count
$overall = if ($failed -eq 0 -and -not $dirty) { 'PASS' } else { 'FAIL' }
$out = New-Object System.Collections.Generic.List[string]
$out.Add("full-gate $overall source=$sha$(if ($dirty) { ' DIRTY (changes or untracked files not in HEAD)' })")
foreach ($r in $results) { $out.Add(('{0,-17} {1,-8} {2,5}s  {3}' -f $r.Name, $r.Status, $r.Secs, $r.Detail)) }
$out.Add("logs: $logDir")
$out | Select-Object -First 15 | ForEach-Object { Write-Output $_ }
[Console]::OutputEncoding = $prevConsoleEncoding
if ($overall -eq 'PASS') { exit 0 } else { exit 1 }
