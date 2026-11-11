<#
.SYNOPSIS
  Run one Codex task (or its single follow-up) non-interactively in ..\cv-compass-wt\<task-id>.

.DESCRIPTION
  Usage:
    scripts\codex-task.ps1 <task-id> <brief-file>
    scripts\codex-task.ps1 <task-id> <brief-file> -Resume <session-id> <followup-file>

  The worktree must already exist (and npm ci must have been run when frontend/ exists).
  Events, stderr and the final message go to build\codex\<task-id>\ in the main checkout.
  At most two Codex calls per task (initial + one resume). Prints at most 15 lines.

  Run it from the main checkout (E:\cv-compass), never from the task worktree it guards.

  Exit codes: 0 ok, 1 usage/run/parse failure, 2 sandbox mismatch,
  3 dependency or protected files touched.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$TaskId,
    [Parameter(Mandatory = $true, Position = 1)][string]$BriefFile,
    [string]$Resume,
    [Parameter(Position = 2)][string]$FollowupFile,
    [int]$TimeoutMinutes = 60
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$prevConsoleEncoding = [Console]::OutputEncoding
$prevInputEncoding = [Console]::InputEncoding
# Briefs are UTF-8 (Vietnamese diacritics): set both directions before stdin is handed to Codex
# and restore the caller's console on every exit path.
[Console]::OutputEncoding = $utf8
[Console]::InputEncoding = $utf8

function Restore-ConsoleEncoding {
    [Console]::OutputEncoding = $prevConsoleEncoding
    [Console]::InputEncoding = $prevInputEncoding
}
# Terminating errors outside Fail (Start-Process, file I/O) must not leave the caller's console in UTF-8.
trap { Restore-ConsoleEncoding; break }

$MaxCalls = 2
# Dependency changes need the project owner's approval (AGENT_WORKFLOW: High, stop and ask).
$DependencyPattern = '(^|/)(build\.gradle[^/]*|settings\.gradle[^/]*|gradle-wrapper\.(properties|jar)|libs\.versions\.toml|package[^/]*\.json|\.npmrc|pyproject\.toml|requirements[^/]*\.(txt|lock|in)|Dockerfile[^/]*)$'
# Agent rules, agent config, CI, this tooling itself and the locked artifact downloader used by the
# worker gate are owner-only (High, stop and ask).
$ProtectedPattern = '^((.*/)?(AGENTS|CLAUDE)\.md|\.claude/.*|\.codex/.*|\.github/.*|docs/AGENT_[^/]*\.md|scripts/(codex-task\.ps1|full-gate\.ps1|codex-result\.schema\.json)|worker/spikes/m6_2_hardening/download_locked\.py)$'
# Never hand test DB settings or anything credential-like to the Codex process. Codex itself
# authenticates through its own login (CODEX_HOME/auth.json), not through environment keys.
$SensitiveEnvPattern = '^(TEST_DB_.*|.*(SECRET|PASSWORD|PASSWD|TOKEN|CREDENTIAL|PRIVATE_KEY|API_KEY|ACCESS_KEY).*)$'

function Fail([string]$message, [int]$code = 1) {
    Write-Output "codex-task: $message"
    Restore-ConsoleEncoding
    exit $code
}

# Quote one argument for CreateProcess using the MSVCRT/Rust command-line parsing rules.
function ConvertTo-NativeArg([string]$value) {
    if ($value -ne '' -and $value -notmatch '[\s"]') { return $value }
    $escaped = [regex]::Replace($value, '(\\*)"', { param($m) ($m.Groups[1].Value * 2) + '\"' })
    $escaped = [regex]::Replace($escaped, '(\\+)$', { param($m) $m.Groups[1].Value * 2 })
    return '"' + $escaped + '"'
}

function Get-FullPath([string]$path) {
    return [System.IO.Path]::GetFullPath($path).TrimEnd('\', '/')
}

function Get-Prop($object, [string]$name) {
    if ($null -eq $object) { return $null }
    $p = $object.PSObject.Properties[$name]
    if ($p) { return $p.Value } else { return $null }
}

function Read-Utf8([string]$path) {
    return [System.IO.File]::ReadAllText($path, $utf8)
}

# --- Validate input -------------------------------------------------------------------------
if ($TaskId -notmatch '^[a-z0-9][a-z0-9-]{0,63}$') { Fail 'task-id must match ^[a-z0-9][a-z0-9-]{0,63}$' }
$isResume = [bool]$Resume
if ($isResume) {
    if ($Resume -notmatch '^[0-9a-fA-F-]{36}$') { Fail 'session-id must be a UUID (no --last)' }
    if (-not $FollowupFile) { Fail '-Resume needs <followup-file>' }
    $promptFile = $FollowupFile
} else {
    if ($FollowupFile) { Fail '<followup-file> is only valid with -Resume' }
    $promptFile = $BriefFile
}
foreach ($f in @($BriefFile, $promptFile)) {
    if (-not (Test-Path -LiteralPath $f -PathType Leaf)) { Fail "file not found: $f" }
}
$promptPath = (Resolve-Path -LiteralPath $promptFile).Path
if ((Read-Utf8 $promptPath).Trim().Length -eq 0) { Fail "prompt file is empty: $promptFile" }

# Resolve the main checkout through the shared git dir so the script works from any worktree.
$gitCommon = (& git -C $PSScriptRoot rev-parse --path-format=absolute --git-common-dir)
if ($LASTEXITCODE -ne 0) { Fail 'not inside a git repository' }
$mainRoot = Split-Path (Get-FullPath $gitCommon.Trim()) -Parent
$worktree = Get-FullPath (Join-Path (Split-Path $mainRoot -Parent) "cv-compass-wt\$TaskId")
if (-not (Test-Path -LiteralPath $worktree -PathType Container)) { Fail "worktree missing: $worktree" }
$wtTop = (& git -C $worktree rev-parse --show-toplevel)
if ($LASTEXITCODE -ne 0 -or (Get-FullPath $wtTop.Trim()) -ne $worktree) { Fail "not a git worktree root: $worktree" }
if ((Test-Path (Join-Path $worktree 'frontend\package.json')) -and
    -not (Test-Path (Join-Path $worktree 'frontend\node_modules'))) {
    Fail 'run npm ci in <worktree>\frontend before calling Codex (Codex has no network)'
}

$schema = Join-Path $PSScriptRoot 'codex-result.schema.json'
$outDir = Join-Path $mainRoot "build\codex\$TaskId"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$sessionFile = Join-Path $outDir 'session-id.txt'
$previousCalls = @(Get-ChildItem -LiteralPath $outDir -Filter 'events-*.jsonl' -ErrorAction SilentlyContinue).Count
if ($previousCalls -ge $MaxCalls) { Fail "call limit reached ($MaxCalls per task)" }
if ($isResume) {
    if (-not (Test-Path $sessionFile)) { Fail 'no recorded session for this task; run the initial call first' }
    if ((Read-Utf8 $sessionFile).Trim() -ne $Resume) { Fail 'session-id does not match the one recorded for this task' }
} elseif ($previousCalls -gt 0) {
    Fail 'initial call already ran; use -Resume <session-id> <followup-file>'
}
$call = $previousCalls + 1
$events = Join-Path $outDir "events-$call.jsonl"
$stderrLog = Join-Path $outDir "stderr-$call.log"
$last = Join-Path $outDir 'last.txt'
# The dependency guard compares against the worktree HEAD recorded before the first call, so
# commits made by Codex are caught as well as uncommitted and untracked files.
$baseFile = Join-Path $outDir 'base-sha.txt'
if ($isResume) {
    if (-not (Test-Path $baseFile)) { Fail 'no recorded base SHA for this task; cannot guard dependency files' }
    $baseSha = (Read-Utf8 $baseFile).Trim()
} else {
    $baseSha = (& git -C $worktree rev-parse HEAD)
    if ($LASTEXITCODE -ne 0) { Fail 'cannot resolve worktree HEAD' }
    $baseSha = $baseSha.Trim()
}
if ($baseSha -notmatch '^[0-9a-f]{40}$') { Fail 'recorded base SHA is malformed' }
# --verify --quiet reports a missing commit through the exit code only, never on stderr.
$null = & git -C $worktree rev-parse --verify --quiet "$baseSha^{commit}"
if ($LASTEXITCODE -ne 0) { Fail 'recorded base SHA is not a commit in the worktree' }
if (-not $isResume) { [System.IO.File]::WriteAllText($baseFile, $baseSha, $utf8) }

$codex = (Get-Command codex.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1)
if (-not $codex) { Fail 'codex.exe not found on PATH' }

# --- Build the command line -----------------------------------------------------------------
$shared = @(
    '--ignore-user-config',
    '-c', 'windows.sandbox="elevated"',
    '-c', 'model_reasoning_effort="medium"',
    '-c', 'shell_environment_policy.inherit="core"',
    '-c', 'sandbox_workspace_write.network_access=false',
    '--json',
    '-o', $last,
    '--output-schema', $schema
)
if ($isResume) {
    # `exec resume` has no -s/-C: pass sandbox_mode explicitly and start in the worktree.
    $argv = @('exec', 'resume') + $shared + @('-c', 'sandbox_mode="workspace-write"', $Resume, '-')
} else {
    $argv = @('exec') + $shared + @('-s', 'workspace-write', '-C', $worktree, '-')
}
$argLine = ($argv | ForEach-Object { ConvertTo-NativeArg $_ }) -join ' '

# --- Run with a scrubbed environment --------------------------------------------------------
$removed = @{}
$names = @([Environment]::GetEnvironmentVariables('Process').Keys | ForEach-Object { [string]$_ })
foreach ($name in $names) {
    if ($name -match $SensitiveEnvPattern) {
        $removed[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
}
$testDbPresent = @($removed.Keys | Where-Object { $_ -like 'TEST_DB_*' }).Count -gt 0
if (Test-Path $last) { Remove-Item -LiteralPath $last }
$exitCode = $null
$timedOut = $false
try {
    $proc = Start-Process -FilePath $codex.Source -ArgumentList $argLine -WorkingDirectory $worktree `
        -RedirectStandardInput $promptPath -RedirectStandardOutput $events -RedirectStandardError $stderrLog `
        -NoNewWindow -PassThru
    $null = $proc.Handle  # keeps ExitCode readable on Windows PowerShell 5.1
    if (-not $proc.WaitForExit($TimeoutMinutes * 60 * 1000)) {
        $timedOut = $true
        & taskkill.exe /PID $proc.Id /T /F | Out-Null
        $proc.WaitForExit()
    }
    $exitCode = $proc.ExitCode
} finally {
    foreach ($name in $removed.Keys) { [Environment]::SetEnvironmentVariable($name, $removed[$name], 'Process') }
}

# --- Summarise ------------------------------------------------------------------------------
$lines = New-Object System.Collections.Generic.List[string]
$mode = if ($isResume) { 'resume' } else { 'initial' }
$lines.Add("codex-task $TaskId call $call/$MaxCalls ($mode) exit=$exitCode$(if ($timedOut) { ' TIMEOUT' })")
# Names and values are never printed; only whether TEST_DB_* existed and was withheld.
$lines.Add("env: withheld $($removed.Count) sensitive var(s); TEST_DB_* $(if ($testDbPresent) { 'present, withheld' } else { 'absent' })")
$result = 0

$eventLines = @()
if (Test-Path $events) { $eventLines = @([System.IO.File]::ReadAllLines($events, $utf8) | Where-Object { $_.Trim() }) }
$sessionId = $null
if ($eventLines.Count -gt 0) {
    try {
        $first = $eventLines[0] | ConvertFrom-Json
        if ($first.type -eq 'thread.started') { $sessionId = [string]$first.thread_id }
    } catch { }
}
if (-not $sessionId) {
    $lines.Add('session: not found in first event')
    $result = 1
} else {
    if ($isResume -and $sessionId -ne $Resume) { $lines.Add("session: resumed id differs ($sessionId)"); $result = 1 }
    if (-not $isResume) { [System.IO.File]::WriteAllText($sessionFile, $sessionId, $utf8) }
    $lines.Add("session: $sessionId")

    # Confirm the sandbox actually applied to the latest turn (resume does not accept -s).
    $codexHome = if ($env:CODEX_HOME) { $env:CODEX_HOME } else { Join-Path $HOME '.codex' }
    $rollout = Get-ChildItem -LiteralPath (Join-Path $codexHome 'sessions') -Recurse -File `
        -Filter "rollout-*-$sessionId.jsonl" -ErrorAction SilentlyContinue | Select-Object -First 1
    $turn = $null
    if ($rollout) {
        $ctx = [System.IO.File]::ReadAllLines($rollout.FullName, $utf8) |
            Where-Object { $_ -match '"type":"turn_context"' } | Select-Object -Last 1
        if ($ctx) { $turn = ($ctx | ConvertFrom-Json).payload }
    }
    if (-not $turn) {
        $lines.Add('sandbox: NOT VERIFIED (turn_context not found)')
        if ($result -eq 0) { $result = 2 }
    } else {
        # StrictMode throws on missing properties; other sandbox modes omit network_access.
        $sp = Get-Prop $turn 'sandbox_policy'
        $spType = Get-Prop $sp 'type'
        $spNet = Get-Prop $sp 'network_access'
        $cwd = [string](Get-Prop $turn 'cwd')
        $cwdOk = $cwd -and ((Get-FullPath $cwd) -eq $worktree)
        $ok = ($spType -eq 'workspace-write') -and ($spNet -eq $false) -and $cwdOk
        $lines.Add("sandbox: $(if ($ok) { 'OK' } else { 'MISMATCH' }) type=$spType network=$spNet cwd=$(if ($cwdOk) { 'worktree' } else { 'OTHER' })")
        if (-not $ok -and $result -eq 0) { $result = 2 }
    }
}

if (Test-Path $last) {
    Copy-Item -LiteralPath $last -Destination (Join-Path $outDir "last-$call.txt") -Force
    try {
        $r = (Read-Utf8 $last) | ConvertFrom-Json
        $checks = @($r.checks_run)
        $byResult = ($checks | Group-Object result | ForEach-Object { "$($_.Count) $($_.Name)" }) -join ', '
        $lines.Add("status: $($r.status) | files: $(@($r.files_changed).Count) | checks: $(if ($byResult) { $byResult } else { 'none' }) | open questions: $(@($r.open_questions).Count)")
        $note = ([string]$r.notes) -replace '\s+', ' '
        # Never drop part of the notes silently: long notes point at the saved copy that holds them in full.
        if ($note.Length -gt 400) { $note = $note.Substring(0, 400) + " ... [truncated; full notes in $(Join-Path $outDir "last-$call.txt")]" }
        if ($note) { $lines.Add("notes: $note") }
    } catch {
        $lines.Add('result: last.txt does not match the schema')
        $result = 1
    }
} else {
    $lines.Add('result: no last message')
    $result = 1
}

# Committed + staged + unstaged since base (renames split into delete/add), plus untracked files.
$committedOrTracked = @(& git -C $worktree -c core.quotepath=off diff --name-only --no-renames $baseSha)
$diffOk = $LASTEXITCODE -eq 0
$untracked = @(& git -C $worktree -c core.quotepath=off ls-files --others --exclude-standard)
$diffOk = $diffOk -and ($LASTEXITCODE -eq 0)
$changed = @(($committedOrTracked + $untracked) | Where-Object { $_ } | Sort-Object -Unique)
$lines.Add("changes since base $($baseSha.Substring(0, 12)): $($changed.Count) path(s)")
$normalized = @($changed | ForEach-Object { $_ -replace '\\', '/' })
$deps = @($normalized | Where-Object { $_ -match $DependencyPattern })
$protected = @($normalized | Where-Object { $_ -match $ProtectedPattern })
if (-not $diffOk) {
    # Keep the summary of the call already spent; an unverified guard is treated like a hit.
    $lines.Add('HIGH: dependency/protected guard NOT VERIFIED (git diff/ls-files failed) - stop and ask the owner')
    $result = 3
} else {
    if ($deps.Count -gt 0) {
        $lines.Add("HIGH: dependency files touched ($($deps -join ', ')) - stop and ask the owner")
        $result = 3
    }
    if ($protected.Count -gt 0) {
        $lines.Add("HIGH: protected paths touched ($($protected -join ', ')) - stop and ask the owner")
        $result = 3
    }
}

if (($exitCode -ne 0 -or $timedOut) -and $result -eq 0) { $result = 1 }
if ($exitCode -ne 0 -and (Test-Path $stderrLog)) {
    $tail = @([System.IO.File]::ReadAllLines($stderrLog, $utf8) | Where-Object { $_.Trim() } | Select-Object -Last 2)
    foreach ($t in $tail) { $lines.Add('stderr: ' + $(if ($t.Length -gt 160) { $t.Substring(0, 160) + '...' } else { $t })) }
}
$lines.Add("logs: $outDir")

$lines | Select-Object -First 15 | ForEach-Object { Write-Output $_ }
Restore-ConsoleEncoding
exit $result
