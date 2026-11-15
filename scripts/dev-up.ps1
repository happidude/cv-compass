<#
.SYNOPSIS
  Start tracked local service windows with an explicit database selection.
.DESCRIPTION
  Test requires session DB_URL/DB_USERNAME/DB_PASSWORD. DryRun only prints commands.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('Dev', 'Test', 'None')][string]$Database,
    [switch]$DryRun, [switch]$SkipBuild, [switch]$SkipFrontend,
    [ValidateRange(1, 65535)][int]$BackendPort = 8080,
    [ValidateRange(1, 65535)][int]$BackendWaitSeconds = 240,
    [switch]$SkipBackend,
    [ValidateRange(1, 65535)][int]$FrontendPort = 5173
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$RunDir = Join-Path $Repo '.dev/run'
$LogDir = Join-Path $Repo '.dev/log'
$Refused = $false; $Failed = $false
function Quote-PS([string]$Text) { return "'" + $Text.Replace("'", "''") + "'" }
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
function Start-Window([string]$Service, [string]$Directory, [string]$Command) {
    $log = Join-Path $LogDir "$Service.log"
    $windowScript = @"
`$Host.UI.RawUI.WindowTitle = 'cv-compass: $Service'
Set-Location -LiteralPath $(Quote-PS $Directory)
`$ErrorActionPreference = 'Continue'
& {
$Command
} 2>&1 | ForEach-Object { "`$_" } | Tee-Object -FilePath $(Quote-PS $log)
"@
    if ($DryRun) {
        Show "$Service window" $windowScript
        Write-Host 'Start-Process powershell.exe -PassThru -ArgumentList @(''-NoExit'', ''-NoProfile'', ''-ExecutionPolicy'', ''Bypass'', ''-EncodedCommand'', <UTF-16LE encoding of the window script>)'
        Write-Host "Rotate .dev/log/$Service.log to $Service.prev.log; write .dev/run/$Service.json after start."
        return
    }
    New-Item -ItemType Directory -Force $RunDir, $LogDir | Out-Null
    if (Test-Path -LiteralPath $log) { Move-Item -LiteralPath $log -Destination (Join-Path $LogDir "$Service.prev.log") -Force }
    # EncodedCommand preserves quotes through Start-Process on Windows PowerShell 5.1.
    $b64 = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($windowScript))
    $process = Start-Process powershell.exe -WorkingDirectory $Directory -PassThru `
        -ArgumentList @('-NoExit', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $b64)
    $native = $null
    for ($attempt = 0; $attempt -lt 20 -and $null -eq $native; $attempt++) {
        $native = Get-CimInstance Win32_Process -Filter "ProcessId = $($process.Id)"
        if (-not $native) { Start-Sleep -Milliseconds 100 }
    }
    if (-not $native -or -not $native.CommandLine) { throw "Cannot record $Service window identity" }
    $record = [ordered]@{
        service = $Service; pid = $process.Id; startTimeUtc = $process.StartTime.ToUniversalTime().ToString('o')
        commandLine = $native.CommandLine; kind = 'windows'; log = $log
    }
    $record | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $RunDir "$Service.json") -Encoding UTF8
}
function Has-Tool([string]$Name) {
    if (Get-Command $Name -ErrorAction SilentlyContinue) { return $true }
    Write-Host "Missing tool: $Name"; return $false
}
if ($DryRun) { Write-Host 'DRY RUN: no tools, processes, probes or file writes are executed.' }
$backendOk = $true; $frontendOk = $true; $databaseOk = $true
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
if (-not $backendOk -or -not $frontendOk -or -not $databaseOk) { $Failed = $true }
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
} else { Write-Host 'SKIP frontend: backend is not ready' }
Write-Host 'Logs: .dev/log/<svc>.log; records: .dev/run/<svc>.json'
Write-Host 'Stop: .\scripts\dev-down.ps1'
Write-Host 'OCR worker: chay thu cong, xem docs/LOCAL_DEV.md#ocr-worker'
Write-Host 'LLM worker: chay thu cong, xem docs/LOCAL_DEV.md#llm-worker'
Write-Host 'Chi chay worker SAU khi backend bao san sang.'
if ($Refused) { exit 3 }
if ($Failed) { exit 1 }
