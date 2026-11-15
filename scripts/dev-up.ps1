<#
.SYNOPSIS
  Start the local CV Compass stack in order: database, backend, frontend, OCR worker (WSL).

.DESCRIPTION
  Each long-running process gets its own PowerShell window so its log stays visible and Ctrl+C stops
  only that process. At the end the script prints (never runs) the commands for the LLM worker in WSL,
  because its token must be typed by the owner into that terminal.

  -Database is mandatory so the target is always explicit:
    Dev   docker compose up -d for infra/docker-compose.yml (container cv-compass-postgres); the backend
          reads backend/.env.properties as usual.
    Test  scripts/test-db.ps1 up (container cv-compass-postgres-test). The backend window maps the User
          TEST_DB_* variables onto DB_URL/DB_USERNAME/DB_PASSWORD by name; values are never printed.
    None  do not touch any database (already running).

  Secrets: the script never generates, writes or prints a secret. Credentials come only from existing
  User environment variables (Test) or the owner's private files (backend/.env.properties, the WSL
  token file read inside WSL by the OCR worker command).

  -DryRun prints every command and touches nothing (no docker, no process, no network).

.EXAMPLE
  .\scripts\dev-up.ps1 -Database Dev -DryRun
.EXAMPLE
  .\scripts\dev-up.ps1 -Database Test -SkipOcrWorker
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('Dev', 'Test', 'None')][string]$Database,
    [switch]$DryRun,
    # Reuse the last bootJar instead of rebuilding.
    [switch]$SkipBuild,
    [switch]$SkipFrontend,
    [switch]$SkipOcrWorker,
    [string]$WslDistro = 'Ubuntu-24.04',
    # Paths inside WSL (owner-private, outside Git). Defaults follow the local worker setup guide.
    [string]$WslRepo = '~/cv-compass',
    [string]$OcrPythonVar = '$PY',
    [string]$OcrValuesFile = '~/cv-ocr-private/values.txt',
    [string]$OcrTokenFile = '~/cv-ocr-private/worker.token',
    [string]$OcrPullConfig = '~/cv-ocr-private/pull.toml',
    [int]$BackendPort = 8080,
    [int]$BackendWaitSeconds = 240
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$BackendDir = Join-Path $Repo 'backend'
$FrontendDir = Join-Path $Repo 'frontend'

function Step([string]$Title) { Write-Output ''; Write-Output "== $Title" }

function Show([string]$Where, [string]$Command) {
    Write-Output "[$Where]"
    $Command -split "`n" | ForEach-Object { Write-Output "  $_" }
}

# Opens a new window that keeps running the command; the command text holds no secret values.
function Start-Window([string]$Title, [string]$Directory, [string]$Command) {
    Show "$Title (new window, in $Directory)" $Command
    if ($DryRun) { return }
    $script = "`$Host.UI.RawUI.WindowTitle = 'cv-compass: $Title'`n$Command"
    Start-Process -FilePath 'powershell.exe' -WorkingDirectory $Directory `
        -ArgumentList @('-NoExit', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', $script) | Out-Null
}

function Wait-Port([int]$Port, [int]$Seconds) {
    if ($DryRun) { Write-Output "  (DryRun) not waiting for 127.0.0.1:$Port"; return }
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        $client = New-Object System.Net.Sockets.TcpClient
        try {
            $async = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
            if ($async.AsyncWaitHandle.WaitOne(1000) -and $client.Connected) {
                Write-Output "  127.0.0.1:$Port is listening"
                return
            }
        } catch {
            # Not listening yet; keep waiting until the deadline.
        } finally {
            $client.Close()
        }
        Start-Sleep -Seconds 2
    }
    throw "Backend is not listening on 127.0.0.1:$Port after $Seconds s; check the backend window."
}

if ($DryRun) { Write-Output 'DRY RUN: printing commands only; nothing is started.' }

Step "1. Database ($Database)"
switch ($Database) {
    'Dev' {
        $cmd = "docker compose -f `"$(Join-Path $Repo 'infra\docker-compose.yml')`" up -d"
        Show 'PowerShell' $cmd
        if (-not $DryRun) {
            & docker compose -f (Join-Path $Repo 'infra\docker-compose.yml') up -d
            if ($LASTEXITCODE -ne 0) { throw "docker compose up failed (exit $LASTEXITCODE)" }
        }
    }
    'Test' {
        $cmd = "& `"$(Join-Path $PSScriptRoot 'test-db.ps1')`" up"
        Show 'PowerShell' $cmd
        if (-not $DryRun) {
            & (Join-Path $PSScriptRoot 'test-db.ps1') up
            if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "test-db.ps1 up failed (exit $LASTEXITCODE)" }
        }
    }
    'None' { Write-Output '  Database untouched.' }
}

Step '2. Backend'
$backend = @()
if ($Database -eq 'Test') {
    # Copy by variable name only: the values never appear in this script's output or the window's command line.
    $backend += "`$env:DB_URL = [Environment]::GetEnvironmentVariable('TEST_DB_URL', 'User')"
    $backend += "`$env:DB_USERNAME = [Environment]::GetEnvironmentVariable('TEST_DB_USERNAME', 'User')"
    $backend += "`$env:DB_PASSWORD = [Environment]::GetEnvironmentVariable('TEST_DB_PASSWORD', 'User')"
}
if (-not $SkipBuild) { $backend += '.\gradlew.bat bootJar --no-daemon' }
$backend += "`$jar = (Get-ChildItem build/libs/*.jar | Where-Object Name -NotLike '*-plain.jar').FullName"
$backend += 'New-Item -ItemType Directory -Force build/tmp/jvm | Out-Null'
$backend += '$socketTmp = (Resolve-Path build/tmp/jvm).Path'
# Quoted -D options: PowerShell splits unquoted ones at the dot. UTC: PostgreSQL rejects Asia/Saigon.
$backend += 'java "-Duser.timezone=UTC" "-Djdk.net.unixdomain.tmpdir=$socketTmp" -jar $jar --cv.import.enabled=false'
Start-Window 'backend' $BackendDir ($backend -join "`n")
Wait-Port $BackendPort $BackendWaitSeconds

if (-not $SkipFrontend) {
    Step '3. Frontend'
    Start-Window 'frontend' $FrontendDir 'npm run dev'
}

if (-not $SkipOcrWorker) {
    Step "4. OCR worker (WSL $WslDistro)"
    # The token is read from the private file inside WSL, exported only into that shell and unset when
    # the worker exits; the window then stays open on a plain shell so the last log lines remain visible.
    # No single quotes in the bash text: it is passed as one CommandLineToArgvW argument with \" escapes.
    $ocr = "source <(grep -E `"^(PY|BUILD|PROFILE)=`" $OcrValuesFile | sed `"s/^/export /`") && " +
        "cd $WslRepo && export CV_COMPASS_OCR_TOKEN=`"`$(cat $OcrTokenFile)`" && " +
        "`"$OcrPythonVar`" worker/run_pull_worker.py --config $OcrPullConfig; " +
        'unset CV_COMPASS_OCR_TOKEN; exec bash'
    $wslArgs = "-d $WslDistro -- bash -lc `"" + $ocr.Replace('"', '\"') + '"'
    Show "ocr-worker (new window)" "wsl.exe $wslArgs"
    if (-not $DryRun) { Start-Process -FilePath 'wsl.exe' -ArgumentList $wslArgs | Out-Null }
}

Step '5. LLM worker (run yourself in a new WSL terminal; see docs/LOCAL_DEV.md section 2)'
Write-Output @"
  wsl -d $WslDistro
  cd $WslRepo && git pull            # the WSL clone must have the latest code of your branch
  cd llm-worker && export PYTHONPATH=src
  read -rs CVC_LLM_WORKER_TOKEN && export CVC_LLM_WORKER_TOKEN   # paste the token; it is not echoed
  python3.14 -m cv_compass_llm check
  python3.14 -m cv_compass_llm pull --once
  python3.14 -m cv_compass_llm pull
"@
Write-Output ''
Write-Output 'Stop: Ctrl+C in each window in reverse order (LLM worker, OCR worker, frontend, backend).'
