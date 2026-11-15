<#
.SYNOPSIS
  Stop only recorded service identities; keep PostgreSQL unless StopDatabase is set.
#>
[CmdletBinding()]
param(
    [ValidateSet('backend', 'frontend', 'ocr-worker', 'llm-worker')]
    [string[]]$Service = @('backend', 'frontend', 'ocr-worker', 'llm-worker'),
    [switch]$StopDatabase,
    [switch]$DryRun
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$RunDir = Join-Path $Repo '.dev/run'
$SessionLlmToken = $env:CVC_LLM_WORKER_TOKEN
Remove-Item Env:CVC_LLM_WORKER_TOKEN -ErrorAction SilentlyContinue
$Failed = $false
function Quote-PS([string]$Text) { return "'" + $Text.Replace("'", "''") + "'" }
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
function Linux-Stop([string]$Name) {
    return @'
set -euo pipefail
unset CVC_LLM_WORKER_TOKEN
file="${XDG_STATE_HOME:-$HOME/.local/state}/cv-compass-dev/run/win-__SERVICE__.rec"
proc_start() {
    local stat rest
    [[ -r /proc/$1/stat ]] || return 1
    stat=$(<"/proc/$1/stat"); rest=${stat##*) }
    read -ra fields <<< "$rest"
    printf '%s' "${fields[19]}"
}
proc_cmd() { tr '\0' ' ' < "/proc/$1/cmdline"; }
if [[ ! -f $file ]]; then echo '__SERVICE__: nothing to stop'; exit 0; fi
if ! { IFS= read -r pid; IFS= read -r start; IFS= read -r cmd; } < "$file"; then
    echo 'SKIP __SERVICE__: invalid record'; rm -- "$file"; exit 4
fi
if [[ ! $pid =~ ^[0-9]+$ || $pid -le 1 || ! -d /proc/$pid ]]; then
    echo 'STALE __SERVICE__: process is no longer running'; rm -- "$file"; exit 0
fi
if [[ $(proc_start "$pid") != "$start" || $(proc_cmd "$pid") != "$cmd" ]]; then
    echo "SKIP __SERVICE__: pid $pid does not match record"; rm -- "$file"; exit 4
fi
for signal in INT TERM KILL; do
    [[ $(proc_start "$pid" || :) == "$start" ]] || break
    if [[ $(proc_cmd "$pid") != "$cmd" ]]; then
        echo "SKIP __SERVICE__: pid $pid does not match record"; rm -- "$file"; exit 4
    fi
    kill -"$signal" -- "-$pid" 2>/dev/null || :
    count=30; [[ $signal != TERM ]] || count=10; [[ $signal != KILL ]] || count=1
    for ((i=0; i<count; i++)); do
        [[ $(proc_start "$pid" || :) == "$start" && -n $(proc_cmd "$pid" 2>/dev/null || :) ]] || break
        sleep 1
    done
done
if [[ $(proc_start "$pid" || :) == "$start" && -n $(proc_cmd "$pid" 2>/dev/null || :) ]]; then
    echo 'SKIP __SERVICE__: process is still running; record retained'; exit 1
fi
rm -f -- "$file"
echo 'STOPPED __SERVICE__'
'@.Replace('__SERVICE__', $Name)
}
try {
    Write-Host 'Wait until no CV is PROCESSING before stopping workers.'
    foreach ($name in @('llm-worker', 'ocr-worker', 'frontend', 'backend')) {
        if ($Service -notcontains $name) { continue }
        $file = Join-Path $RunDir "$name.json"
        if (-not (Test-Path -LiteralPath $file)) { Write-Host "${name}: nothing to stop"; continue }
        $record = Get-Content -LiteralPath $file -Raw | ConvertFrom-Json
        if ($DryRun) {
            Write-Host "DRY RUN ${name}: verify PID, StartTime UTC and Win32_Process.CommandLine against .dev/run/$name.json"
            if ($record.kind -eq 'wsl') { Write-Host (Linux-Stop $name) }
            Write-Host '  If matched: stop verified descendants before the window using Stop-Process -Id; remove record'
            continue
        }
        if (-not (Get-Process -Id $record.pid -ErrorAction SilentlyContinue)) {
            Write-Host "STALE ${name}: process is no longer running"
            # A worker may outlive its terminal; still verify its independent Linux record below.
            if ($record.kind -ne 'wsl') { Remove-Item -LiteralPath $file; continue }
        } elseif (-not (Matches $record)) {
            Write-Host "SKIP ${name}: pid $($record.pid) does not match record"
            Remove-Item -LiteralPath $file; continue
        }
        if ($record.kind -eq 'wsl') {
            $bash = Linux-Stop $name
            $b64 = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($bash.Replace("`r`n", "`n")))
            $command = '& wsl.exe -d ' + (Quote-PS $record.distro) + " --exec bash -l -c ('source <(printf %s ' + '$b64' + ' | base64 -d)')"
            $saved = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
            try { & ([scriptblock]::Create($command)); $code = $LASTEXITCODE } finally { $ErrorActionPreference = $saved }
            if ($code -eq 4) { Remove-Item -LiteralPath $file; continue }
            if ($code -ne 0) { Write-Host "SKIP ${name}: WSL stop failed (exit $code); record retained"; $Failed = $true; continue }
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
} finally { $env:CVC_LLM_WORKER_TOKEN = $SessionLlmToken }
if ($Failed) { exit 1 }
