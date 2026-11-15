<#
.SYNOPSIS
  Start tracked local service windows with an explicit database selection.
.DESCRIPTION
  Test requires session DB_URL/DB_USERNAME/DB_PASSWORD. DryRun only prints commands.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('Dev', 'Test', 'None')][string]$Database,
    [switch]$DryRun, [switch]$SkipBuild, [switch]$SkipFrontend, [switch]$SkipOcrWorker,
    [string]$WslDistro = 'Ubuntu-24.04', [string]$WslRepo = '~/cv-compass',
    [string]$OcrPythonVar = '$PY',
    [string]$OcrValuesFile = '~/cv-ocr-private/values.txt',
    [string]$OcrTokenFile = '~/cv-ocr-private/worker.token',
    [string]$OcrPullConfig = '~/cv-ocr-private/pull.toml',
    [ValidateRange(1, 65535)][int]$BackendPort = 8080,
    [ValidateRange(1, 65535)][int]$BackendWaitSeconds = 240,
    [switch]$SkipBackend, [switch]$WithLlmWorker,
    [ValidateRange(1, 65535)][int]$FrontendPort = 5173
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$RunDir = Join-Path $Repo '.dev/run'
$LogDir = Join-Path $Repo '.dev/log'
$Refused = $false; $Failed = $false
$SessionLlmToken = $env:CVC_LLM_WORKER_TOKEN
$OriginalWslEnv = $env:WSLENV
Remove-Item Env:CVC_LLM_WORKER_TOKEN -ErrorAction SilentlyContinue
function Quote-PS([string]$Text) { return "'" + $Text.Replace("'", "''") + "'" }
function Quote-Bash([string]$Text) { return "'" + $Text.Replace("'", "'" + '"' + "'" + '"' + "'") + "'" }
function Bash-Path([string]$Text) {
    if ($Text.StartsWith('~/')) { return '"$HOME"/' + (Quote-Bash $Text.Substring(2)) }
    return Quote-Bash $Text
}
function Show([string]$Label, [string]$Command) { Write-Host "[$Label]"; Write-Host $Command }
function Invoke-Native([scriptblock]$Command) {
    $saved = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { & $Command; $script:NativeExitCode = $LASTEXITCODE } finally { $ErrorActionPreference = $saved }
}
function Test-Port([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $pending = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        return ($pending.AsyncWaitHandle.WaitOne(500) -and $client.Connected)
    } catch { return $false } finally { $client.Close() }
}
function Can-Start([string]$Service, [int]$Port = 0) {
    if ($DryRun) { return $true }
    $file = Join-Path $RunDir "$Service.json"
    if (Test-Path -LiteralPath $file) {
        $record = Get-Content -LiteralPath $file -Raw | ConvertFrom-Json
        $process = Get-Process -Id $record.pid -ErrorAction SilentlyContinue
        $native = Get-CimInstance Win32_Process -Filter "ProcessId = $($record.pid)"
        if ($process -and $native -and $process.StartTime.ToUniversalTime().ToString('o') -eq $record.startTimeUtc -and $native.CommandLine -ceq $record.commandLine) {
            Write-Host "REFUSED ${Service}: pid $($record.pid) is already running"
            $script:Refused = $true; return $false
        }
        if ($process) { Write-Host "SKIP ${Service}: pid $($record.pid) does not match record" }
        else { Write-Host "STALE ${Service}: process is no longer running" }
        Remove-Item -LiteralPath $file
    }
    if ($Port -and (Test-Port $Port)) {
        Write-Host "REFUSED ${Service}: port $Port is already in use"
        $script:Refused = $true; return $false
    }
    return $true
}
function Set-LlmEnvironment {
    $env:CVC_LLM_WORKER_TOKEN = $SessionLlmToken
    $entries = @($OriginalWslEnv -split ':' | Where-Object { $_ -and ($_ -split '/')[0] -ne 'CVC_LLM_WORKER_TOKEN' })
    $env:WSLENV = (@($entries) + 'CVC_LLM_WORKER_TOKEN/u') -join ':'
}
function Reset-ChildEnvironment {
    Remove-Item Env:CVC_LLM_WORKER_TOKEN -ErrorAction SilentlyContinue
    $env:WSLENV = $OriginalWslEnv
}
function Wsl-Command([string]$Bash) {
    if ($Bash -match '[^\x00-\x7f]') { throw 'WSL script must be ASCII; use ASCII WSL paths.' }
    $b64 = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($Bash.Replace("`r`n", "`n")))
    return '& wsl.exe -d ' + (Quote-PS $WslDistro) + " --exec bash -l -c ('source <(printf %s ' + '$b64' + ' | base64 -d)')"
}
function Start-Window([string]$Service, [string]$Directory, [string]$Command, [string]$Kind = 'windows') {
    $log = Join-Path $LogDir "$Service.log"
    $prefix = 'Remove-Item Env:CVC_LLM_WORKER_TOKEN -ErrorAction SilentlyContinue'
    if ($Service -eq 'llm-worker') { $prefix = '' }
    $windowScript = @"
$prefix
`$Host.UI.RawUI.WindowTitle = 'cv-compass: $Service'
Set-Location -LiteralPath $(Quote-PS $Directory)
`$ErrorActionPreference = 'Continue'
& {
$Command
} 2>&1 | ForEach-Object { "`$_" } | Tee-Object -FilePath $(Quote-PS $log)
"@
    if ($DryRun) {
        $readableScript = $windowScript
        if ($Kind -eq 'wsl') {
            $readableCommand = '& wsl.exe -d ' + (Quote-PS $WslDistro) + ' --exec bash -l -c <decoded script printed above>'
            $readableScript = $windowScript.Replace($Command, $readableCommand)
        }
        Show "$Service window" $readableScript
        Write-Host 'Start-Process powershell.exe -PassThru -ArgumentList @(''-NoExit'', ''-NoProfile'', ''-ExecutionPolicy'', ''Bypass'', ''-EncodedCommand'', <UTF-16LE encoding of the window script>)'
        Write-Host "Rotate .dev/log/$Service.log to $Service.prev.log; write .dev/run/$Service.json after start."
        return
    }
    New-Item -ItemType Directory -Force $RunDir, $LogDir | Out-Null
    if (Test-Path -LiteralPath $log) { Move-Item -LiteralPath $log -Destination (Join-Path $LogDir "$Service.prev.log") -Force }
    # EncodedCommand preserves quotes through Start-Process on Windows PowerShell 5.1.
    $b64 = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($windowScript))
    try {
        if ($Service -eq 'llm-worker') { Set-LlmEnvironment }
        $process = Start-Process powershell.exe -WorkingDirectory $Directory -PassThru `
            -ArgumentList @('-NoExit', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $b64)
    } finally { Reset-ChildEnvironment }
    $native = $null
    for ($attempt = 0; $attempt -lt 20 -and $null -eq $native; $attempt++) {
        $native = Get-CimInstance Win32_Process -Filter "ProcessId = $($process.Id)"
        if (-not $native) { Start-Sleep -Milliseconds 100 }
    }
    if (-not $native -or -not $native.CommandLine) { throw "Cannot record $Service window identity" }
    $record = [ordered]@{
        service = $Service; pid = $process.Id; startTimeUtc = $process.StartTime.ToUniversalTime().ToString('o')
        commandLine = $native.CommandLine; kind = $Kind; log = $log
    }
    if ($Kind -eq 'wsl') {
        $record.distro = $WslDistro
        $record.linuxRecord = '${XDG_STATE_HOME:-$HOME/.local/state}/cv-compass-dev/run/win-' + $Service + '.rec'
    }
    $record | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $RunDir "$Service.json") -Encoding UTF8
}
$LinuxIdentity = @'
proc_start() {
    local stat rest
    [[ -r /proc/$1/stat ]] || return 1
    stat=$(<"/proc/$1/stat"); rest=${stat##*) }
    read -ra fields <<< "$rest"
    printf '%s' "${fields[19]}"
}
proc_cmd() { tr '\0' ' ' < "/proc/$1/cmdline"; }
'@
function Worker-Guard([string]$Service) {
    $pattern = '[r]un_pull_worker.py'
    if ($Service -eq 'llm-worker') { $pattern = '[c]v_compass_llm pull' }
    return @'
set -euo pipefail
unset CVC_LLM_WORKER_TOKEN
for tool in setsid pgrep; do
    command -v "$tool" >/dev/null || { echo 'SKIP __SERVICE__: setsid and pgrep are required'; exit 1; }
done
__IDENTITY__
for file in "${XDG_STATE_HOME:-$HOME/.local/state}/cv-compass-dev/run/win-__SERVICE__.rec" __REPO__/.dev/run/__SERVICE__.rec; do
    [[ -f $file ]] || continue
    { IFS= read -r pid; IFS= read -r start; IFS= read -r cmd; } < "$file" || continue
    if [[ $pid =~ ^[0-9]+$ && -d /proc/$pid ]]; then
        if [[ $(proc_start "$pid") == "$start" && $(proc_cmd "$pid") == "$cmd" ]]; then
            echo "REFUSED __SERVICE__: pid $pid is already running"; exit 3
        fi
        echo "SKIP __SERVICE__: pid $pid does not match record"
    else
        echo 'STALE __SERVICE__: process is no longer running'
    fi
    rm -- "$file"
done
if command -v pgrep >/dev/null && pgrep -f '__PATTERN__' >/dev/null; then
    echo 'WARNING __SERVICE__: an external worker is running; no additional worker started'; exit 4
fi
'@.Replace('__IDENTITY__', $LinuxIdentity).Replace('__SERVICE__', $Service).Replace('__REPO__', (Bash-Path $WslRepo)).Replace('__PATTERN__', $pattern)
}
function Worker-Script([string]$Service, [string]$Command) {
    $guard = Worker-Guard $Service
    $launch = 'setsid env -u CVC_LLM_WORKER_TOKEN bash -c ' + (Quote-Bash $Command) + ' &'
    if ($Service -eq 'llm-worker') {
        $launch = '( export CVC_LLM_WORKER_TOKEN="$worker_token"; exec setsid bash -c ' + (Quote-Bash $Command) + ' ) &'
    }
    return "set -euo pipefail`n" + 'worker_token=${CVC_LLM_WORKER_TOKEN-}' + "`n" + $guard + "`n" + @'
record_dir="${XDG_STATE_HOME:-$HOME/.local/state}/cv-compass-dev/run"
mkdir -p -- "$record_dir"
file="$record_dir/win-__SERVICE__.rec"
cd -- __REPO__
__LAUNCH__
pid=$!
sleep 1
start=$(proc_start "$pid")
cmd=$(proc_cmd "$pid")
[[ -n $cmd && $cmd != *$'\n'* ]] || { echo 'SKIP __SERVICE__: worker exited before recording'; exit 1; }
printf '%s\n%s\n%s\n' "$pid" "$start" "$cmd" > "$file"
set +e
wait "$pid"
code=$?
# Keep a newer owner's record if it replaced ours.
if [[ -f $file ]] && [[ $(head -n 1 -- "$file") == "$pid" ]]; then rm -- "$file"; fi
exit "$code"
'@.Replace('__SERVICE__', $Service).Replace('__REPO__', (Bash-Path $WslRepo)).Replace('__LAUNCH__', $launch)
}
function Start-Worker([string]$Service, [string]$Command) {
    if (-not (Can-Start $Service)) { return }
    $guard = Worker-Guard $Service
    Show "$Service preflight (WSL $WslDistro)" $guard
    if (-not $DryRun) {
        $guardCommand = Wsl-Command $guard
        Invoke-Native { & ([scriptblock]::Create($guardCommand)) }
        $code = $NativeExitCode
        if ($code -eq 3) { $script:Refused = $true; return }
        if ($code -ne 0) {
            if ($code -ne 4) { $script:Failed = $true }
            return
        }
    }
    if ($Service -eq 'llm-worker') {
        $check = "set -euo pipefail`ncd -- " + (Bash-Path "$WslRepo/llm-worker") + "`nexport PYTHONPATH=src`npython3.14 -m cv_compass_llm check"
        Show 'LLM check (token inherited, never printed)' $check
        if (-not $DryRun) {
            try {
                Set-LlmEnvironment
                $checkCommand = Wsl-Command $check
                Invoke-Native { & ([scriptblock]::Create($checkCommand)) }
                $code = $NativeExitCode
            } finally { Reset-ChildEnvironment }
            if ($code -ne 0) { Write-Host "SKIP llm-worker: check FAIL (exit $code)"; $script:Failed = $true; return }
        }
    }
    $bash = Worker-Script $Service $Command
    Show "$Service window (decoded WSL script; Tee to .dev/log/$Service.log)" $bash
    if ($DryRun) { Write-Host ('& wsl.exe -d ' + (Quote-PS $WslDistro) + ' --exec bash -l -c <script above; base64 transport at runtime>') }
    Start-Window $Service $Repo (Wsl-Command $bash) 'wsl'
}
function Has-Tool([string]$Name) {
    if (Get-Command $Name -ErrorAction SilentlyContinue) { return $true }
    Write-Host "Missing tool: $Name"; return $false
}
try {
    if ($DryRun) { Write-Host 'DRY RUN: no tools, processes, probes or file writes are executed.' }
    Write-Host ('CVC_LLM_WORKER_TOKEN: ' + $(if ($SessionLlmToken) { 'set' } else { 'missing' }))
    $backendOk = $true; $frontendOk = $true; $workersOk = $true; $databaseOk = $true
    if (-not $SkipBackend) {
        $javaExists = Has-Tool 'java'
        Show 'backend preflight' 'java -version # requires major 25'
        if (-not $DryRun) {
            if (-not $javaExists) { $backendOk = $false }
            else {
                $version = Invoke-Native { & java -version 2>&1 }
                if ($NativeExitCode -ne 0 -or ($version -join "`n") -notmatch 'version "25([."]|$)') { Write-Host 'SKIP backend: Java 25 is required'; $backendOk = $false }
            }
        }
        if ($Database -eq 'Test') {
            $credentialsOk = $env:DB_URL -and $env:DB_USERNAME -and $env:DB_PASSWORD -and
                $env:DB_URL -match '^jdbc:postgresql://(127\.0\.0\.1|localhost):5433/cv_compass_test([?].*)?$'
            if (-not $credentialsOk) {
                Write-Host 'SKIP backend: Test requires session DB_URL/DB_USERNAME/DB_PASSWORD and a loopback test URL on 5433 (values hidden)'
                if (-not $DryRun) { $backendOk = $false }
            }
        } elseif ($Database -eq 'Dev' -and ($env:DB_URL -or $env:DB_USERNAME -or $env:DB_PASSWORD)) { Write-Host 'WARNING: session DB_* override backend/.env.properties' }
    }
    if (-not $SkipFrontend) {
        $nodeExists = Has-Tool 'node'; $npmExists = Has-Tool 'npm.cmd'
        $modulesExist = Test-Path -LiteralPath (Join-Path $Repo 'frontend/node_modules') -PathType Container
        if (-not $modulesExist) { Write-Host 'SKIP frontend: frontend/node_modules missing; run npm ci in frontend/' }
        if (-not $DryRun -and (-not $nodeExists -or -not $npmExists -or -not $modulesExist)) { $frontendOk = $false }
    }
    if ($Database -ne 'None') {
        $dockerExists = Has-Tool 'docker'
        if (-not $DryRun -and -not $dockerExists) { $databaseOk = $false }
    }
    if (-not $SkipOcrWorker -or $WithLlmWorker) {
        $wslExists = Has-Tool 'wsl.exe'
        Show 'worker preflight' ('wsl.exe -l -q # requires ' + $WslDistro)
        if (-not $DryRun) {
            if (-not $wslExists) { $workersOk = $false }
            else {
                $distros = Invoke-Native { & wsl.exe -l -q }
                if ($NativeExitCode -ne 0 -or @($distros | ForEach-Object { ($_ -replace "`0", '').Trim() }) -notcontains $WslDistro) {
                    Write-Host 'SKIP workers: requested WSL distro is unavailable'; $workersOk = $false
                }
            }
        }
    }
    if (-not $backendOk -or -not $frontendOk -or -not $workersOk -or -not $databaseOk) { $Failed = $true }
    if ($databaseOk) {
        switch ($Database) {
            'Dev' {
                $compose = Join-Path $Repo 'infra/docker-compose.yml'
                Show 'database Dev' ('docker compose -f ' + (Quote-PS $compose) + ' up -d')
                if (-not $DryRun) { Invoke-Native { & docker compose -f $compose up -d }; if ($NativeExitCode -ne 0) { $databaseOk = $false; $Failed = $true } }
            }
            'Test' {
                Show 'database Test' ('& ' + (Quote-PS (Join-Path $PSScriptRoot 'test-db.ps1')) + ' up')
                if (-not $DryRun) { & (Join-Path $PSScriptRoot 'test-db.ps1') up; if ($LASTEXITCODE -ne 0) { $databaseOk = $false; $Failed = $true } }
            }
            'None' { Write-Host 'Database untouched.' }
        }
    }
    if (-not $DryRun) {
        New-Item -ItemType Directory -Force $RunDir | Out-Null
        @{ mode = $Database } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $RunDir 'database.json') -Encoding UTF8
    }
    $backendReady = $false
    if (-not $SkipBackend -and $backendOk -and $databaseOk -and (Can-Start 'backend' $BackendPort)) {
        $backend = @()
        if (-not $SkipBuild) { $backend += '.\gradlew.bat bootJar --no-daemon'; $backend += 'if ($LASTEXITCODE -ne 0) { throw "bootJar failed" }' }
        $backend += '$jars = @(Get-ChildItem build/libs/*.jar | Where-Object Name -NotLike "*-plain.jar")'
        $backend += 'if ($jars.Count -ne 1) { throw "Expected exactly one non-plain jar" }'
        $backend += '$jar = $jars[0].FullName'
        $backend += 'New-Item -ItemType Directory -Force build/tmp/jvm | Out-Null'
        $backend += '$socketTmp = (Resolve-Path build/tmp/jvm).Path'
        # Splat Java options so dotted -D names remain single arguments in PowerShell 5.1.
        $backend += '$javaArgs = @(''-Duser.timezone=UTC'', "-Djdk.net.unixdomain.tmpdir=$socketTmp", ''-jar'', $jar, ''--cv.import.enabled=false'')'
        $backend += '& java @javaArgs'
        Start-Window 'backend' (Join-Path $Repo 'backend') ($backend -join "`n")
        if (-not $DryRun) {
            $deadline = (Get-Date).AddSeconds($BackendWaitSeconds)
            while ((Get-Date) -lt $deadline) {
                if (Test-Port $BackendPort) { $backendReady = $true; break }
                Start-Sleep -Seconds 2
            }
            if (-not $backendReady) { Write-Host 'SKIP dependents: backend port did not become ready; see backend log'; $Failed = $true }
        }
    } elseif (-not $DryRun) { $backendReady = Test-Port $BackendPort }
    if ($DryRun) { Write-Host "Wait for backend port $BackendPort (up to $BackendWaitSeconds seconds; no probe in DryRun)."; $backendReady = $true }
    if ($backendReady) {
        if (-not $SkipFrontend -and $frontendOk -and (Can-Start 'frontend' $FrontendPort)) {
            Start-Window 'frontend' (Join-Path $Repo 'frontend') "& npm.cmd run dev -- --port $FrontendPort --strictPort"
        }
        if (-not $SkipOcrWorker -and $workersOk) {
            $pythonCommand = Quote-Bash $OcrPythonVar
            if ($OcrPythonVar -match '^\$[A-Za-z_][A-Za-z0-9_]*$') { $pythonCommand = '"' + $OcrPythonVar + '"' }
            $ocr = "set -euo pipefail`nsource <(grep -E '^(PY|BUILD|PROFILE)=' " + (Bash-Path $OcrValuesFile) + " | sed 's/^/export /')" +
                "`n" + 'export CV_COMPASS_OCR_TOKEN="$(cat -- ' + (Bash-Path $OcrTokenFile) + ')"' +
                "`nexec " + $pythonCommand + ' worker/run_pull_worker.py --config ' + (Bash-Path $OcrPullConfig)
            Start-Worker 'ocr-worker' $ocr
        }
        if ($WithLlmWorker -and $workersOk) {
            if (-not $SessionLlmToken -and -not $DryRun) { Write-Host 'SKIP llm-worker: session CVC_LLM_WORKER_TOKEN is missing; see docs/LOCAL_DEV.md' }
            else {
                if (-not $SessionLlmToken) { Write-Host 'DRY RUN llm-worker: token missing; check/pull below are conditional and would be skipped.' }
                Start-Worker 'llm-worker' "set -euo pipefail`ncd llm-worker`nexport PYTHONPATH=src`nexec python3.14 -m cv_compass_llm pull"
            }
        }
    } else { Write-Host 'SKIP frontend/workers: backend is not ready' }
    if (-not $WithLlmWorker) {
        Write-Host @"
LLM worker (optional; see docs/LOCAL_DEV.md section 2):
  wsl -d $WslDistro
  cd $WslRepo/llm-worker
  export PYTHONPATH=src
  read -rs CVC_LLM_WORKER_TOKEN && export CVC_LLM_WORKER_TOKEN
  python3.14 -m cv_compass_llm check
  python3.14 -m cv_compass_llm pull --once
  python3.14 -m cv_compass_llm pull
Or set the session token and rerun dev-up.ps1 with -WithLlmWorker.
"@
    }
    Write-Host 'Logs: .dev/log/<svc>.log; records: .dev/run/<svc>.json'
    Write-Host 'Stop: .\scripts\dev-down.ps1 (wait until no CV is PROCESSING)'
} finally {
    $env:CVC_LLM_WORKER_TOKEN = $SessionLlmToken
    $env:WSLENV = $OriginalWslEnv
}
if ($Refused) { exit 3 }
if ($Failed) { exit 1 }
