<#
.SYNOPSIS
  Stop only recorded service identities; keep PostgreSQL unless StopDatabase is set.
#>
[CmdletBinding()]
param(
    [ValidateSet('backend', 'frontend')]
    [string[]]$Service = @('backend', 'frontend'),
    [switch]$StopDatabase,
    [switch]$DryRun
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$RunDir = Join-Path $Repo '.dev/run'
$Failed = $false
function Matches($Record) {
    $process = Get-Process -Id $Record.pid -ErrorAction SilentlyContinue
    if (-not $process) { return $null }
    $native = Get-CimInstance Win32_Process -Filter "ProcessId = $($Record.pid)"
    if ($null -ne $native -and $process.StartTime.ToUniversalTime().ToString('o') -eq $Record.startTimeUtc -and $native.CommandLine -ceq $Record.commandLine) { return $native }
    return $null
}
function Stop-Tree($NativeParent) {
    $current = Get-CimInstance Win32_Process -Filter "ProcessId = $($NativeParent.ProcessId)"
    if (-not $current -or $current.CreationDate -ne $NativeParent.CreationDate -or $current.CommandLine -cne $NativeParent.CommandLine) { return }
    $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $($NativeParent.ProcessId)")
    foreach ($child in $children) {
        # A reused parent PID must not capture children from an earlier lifetime.
        if ($child.CreationDate -lt $NativeParent.CreationDate) { continue }
        $current = Get-CimInstance Win32_Process -Filter "ProcessId = $($child.ProcessId)"
        if (-not $current -or $current.CreationDate -ne $child.CreationDate -or $current.CommandLine -cne $child.CommandLine) { continue }
        Stop-Tree $current
    }
    $current = Get-CimInstance Win32_Process -Filter "ProcessId = $($NativeParent.ProcessId)"
    if ($current -and $current.CreationDate -eq $NativeParent.CreationDate -and $current.CommandLine -ceq $NativeParent.CommandLine) {
        Stop-Process -Id $NativeParent.ProcessId -ErrorAction SilentlyContinue
    }
}
$legacyFiles = @(Get-ChildItem -LiteralPath $RunDir -File -ErrorAction SilentlyContinue | Where-Object {
    $_.Extension -eq '.json' -or $_.Name -like '*-worker.rec'
})
foreach ($legacyFile in $legacyFiles) {
    $isWorker = $legacyFile.Name -in @('ocr-worker.json', 'llm-worker.json') -or $legacyFile.Name -like '*-worker.rec'
    $isWsl = $false
    if ($legacyFile.Extension -eq '.json') {
        try {
            $legacyRecord = Get-Content -LiteralPath $legacyFile.FullName -Raw | ConvertFrom-Json
            $isWsl = $legacyRecord.PSObject.Properties['kind'] -and $legacyRecord.kind -eq 'wsl'
        } catch {
            # Keep malformed worker records for manual inspection too.
        }
    }
    if ($isWorker -or $isWsl) {
        Write-Host "WARNING $($legacyFile.BaseName): legacy worker record kept; dung thu cong bang Ctrl+C trong cua so worker ($($legacyFile.FullName))"
        if ($isWsl) {
            Write-Host ('  Also check in WSL: ${XDG_STATE_HOME:-$HOME/.local/state}/cv-compass-dev/run/win-' + $legacyFile.BaseName + '.rec (kept; stop manually).')
        }
    }
}
foreach ($name in @('frontend', 'backend')) {
    if ($Service -notcontains $name) { continue }
    $file = Join-Path $RunDir "$name.json"
    if (-not (Test-Path -LiteralPath $file)) { Write-Host "${name}: nothing to stop"; continue }
    $record = Get-Content -LiteralPath $file -Raw | ConvertFrom-Json
    if ($record.PSObject.Properties['kind'] -and $record.kind -eq 'wsl') { continue }
    if ($DryRun) {
        Write-Host "DRY RUN ${name}: verify PID, StartTime UTC and Win32_Process.CommandLine against .dev/run/$name.json"
        Write-Host '  If matched: stop verified descendants before the window using Stop-Process -Id; remove record'
        continue
    }
    if (-not (Get-Process -Id $record.pid -ErrorAction SilentlyContinue)) {
        Write-Host "STALE ${name}: process is no longer running"
        Remove-Item -LiteralPath $file; continue
    } elseif (-not (Matches $record)) {
        Write-Host "SKIP ${name}: pid $($record.pid) does not match record"
        Remove-Item -LiteralPath $file; continue
    }
    $native = Matches $record
    if ($native) {
        Stop-Tree $native
        if (Matches $record) {
            Write-Host "SKIP ${name}: process is still running; record retained"
            $Failed = $true; continue
        }
    } elseif (Get-Process -Id $record.pid -ErrorAction SilentlyContinue) {
        Write-Host "SKIP ${name}: pid $($record.pid) does not match record"
        Remove-Item -LiteralPath $file; continue
    }
    Remove-Item -LiteralPath $file
    Write-Host "STOPPED $name"
}
if ($StopDatabase) {
    $file = Join-Path $RunDir 'database.json'
    if (-not (Test-Path -LiteralPath $file)) { Write-Host 'Database: nothing to stop (no mode record)' }
    else {
        $mode = (Get-Content -LiteralPath $file -Raw | ConvertFrom-Json).mode
        $container = switch ($mode) { 'Dev' { 'cv-compass-postgres' }; 'Test' { 'cv-compass-postgres-test' }; 'None' { '' }; default { throw 'Invalid database mode record' } }
        if ($container) {
            Write-Host "docker stop $container"
            if (-not $DryRun) {
                & docker stop $container
                if ($LASTEXITCODE -ne 0) { $Failed = $true }
            }
        }
    }
} else { Write-Host 'Database kept running (use -StopDatabase to stop the recorded container).' }
if ($Failed) { exit 1 }
