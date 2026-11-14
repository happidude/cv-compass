<#
.SYNOPSIS
  Manage the dedicated PostgreSQL 17 container for backend integration tests.

.DESCRIPTION
  up     Ensure TEST_DB_* credentials exist, start the container, wait until healthy, run SELECT 1.
  status Show container state/health and run SELECT 1 with the test credentials.
  stop   Stop the container. Data volume is kept; this script has no command that deletes data.

  The first run generates a 32-hex-character password with a cryptographic RNG and stores
  TEST_DB_URL / TEST_DB_USERNAME / TEST_DB_PASSWORD as Windows User environment variables (and for
  the current process). Later runs reuse them. The password is never printed.
#>
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('up', 'status', 'stop')]
    [string]$Command,
    # Only for 'up': allow overwriting User TEST_DB_* variables that point at another database.
    [switch]$ReplaceExisting
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ContainerName = 'cv-compass-postgres-test'
$VolumeName = 'cv-compass-postgres-test-data'
$DbName = 'cv_compass_test'
$DbUser = 'cvcompass_test'
$ExpectedUrl = 'jdbc:postgresql://127.0.0.1:5433/cv_compass_test'
$ComposeFile = Join-Path $PSScriptRoot '..\infra\test-db\compose.yaml'

# Native commands writing to stderr must not become terminating errors under 'Stop' (PowerShell 5.1).
# Callers read the global $LASTEXITCODE afterwards.
function Invoke-Native([scriptblock]$Block) {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Block
    } finally {
        $ErrorActionPreference = $saved
    }
}

function Get-UserEnv([string]$Name) {
    return [Environment]::GetEnvironmentVariable($Name, 'User')
}

function New-TestDbPassword {
    $bytes = New-Object byte[] 16
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return (($bytes | ForEach-Object { $_.ToString('x2') }) -join '')
}

function Test-VolumeExists {
    Invoke-Native { docker volume inspect $VolumeName *> $null }
    return $LASTEXITCODE -eq 0
}

function Test-StoredCredentials {
    return ((Get-UserEnv 'TEST_DB_URL') -eq $ExpectedUrl) -and
        ((Get-UserEnv 'TEST_DB_USERNAME') -eq $DbUser) -and
        [bool](Get-UserEnv 'TEST_DB_PASSWORD')
}

function Set-ProcessCredentials {
    $env:TEST_DB_URL = Get-UserEnv 'TEST_DB_URL'
    $env:TEST_DB_USERNAME = Get-UserEnv 'TEST_DB_USERNAME'
    $env:TEST_DB_PASSWORD = Get-UserEnv 'TEST_DB_PASSWORD'
}

function Initialize-Credentials {
    if (Test-StoredCredentials) {
        Set-ProcessCredentials
        Write-Output 'credentials: reused existing User environment variables'
        return
    }
    if (Test-VolumeExists) {
        # The volume was initialised with some earlier password; a fresh one would never match it.
        throw "Volume $VolumeName exists but User TEST_DB_* variables do not match this setup. Refusing to generate a new password; restore the variables or ask the project owner."
    }
    $existing = @('TEST_DB_URL', 'TEST_DB_USERNAME', 'TEST_DB_PASSWORD') | Where-Object { Get-UserEnv $_ }
    if ($existing -and -not $ReplaceExisting) {
        # Overwriting would silently lose credentials for whatever database they point at today.
        throw "User TEST_DB_* variables already exist for another database ($($existing -join ', ')). Re-run with -ReplaceExisting to switch them to this container."
    }
    $password = New-TestDbPassword
    [Environment]::SetEnvironmentVariable('TEST_DB_URL', $ExpectedUrl, 'User')
    [Environment]::SetEnvironmentVariable('TEST_DB_USERNAME', $DbUser, 'User')
    [Environment]::SetEnvironmentVariable('TEST_DB_PASSWORD', $password, 'User')
    Set-ProcessCredentials
    Write-Output 'credentials: generated new password and set TEST_DB_URL/TEST_DB_USERNAME/TEST_DB_PASSWORD (User scope); open a new terminal to see them'
}

function Get-ContainerState {
    $state = Invoke-Native { docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' $ContainerName 2>$null }
    if ($LASTEXITCODE -ne 0 -or -not $state) { return 'absent' }
    return ([string]$state).Trim()
}

function Wait-Healthy([int]$TimeoutSeconds = 120) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ((Get-ContainerState) -eq 'running healthy') { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Test-Select1 {
    # Write-Host keeps messages out of the boolean return value.
    if (-not $env:TEST_DB_PASSWORD) { Write-Host 'select 1: SKIPPED (TEST_DB_PASSWORD not set)'; return $false }
    # Connect over TCP to the container's own (first) address so password (scram) auth is exercised;
    # the image trusts the local socket and 127.0.0.1. PGPASSWORD is forwarded by name, never on the command line.
    $env:PGPASSWORD = $env:TEST_DB_PASSWORD
    try {
        $out = Invoke-Native { docker exec -e PGPASSWORD $ContainerName sh -c "psql -h `$(hostname -i | cut -d ' ' -f 1) -U $DbUser -d $DbName -tAc SELECT\ 1" 2>$null }
        $code = $LASTEXITCODE
    } finally {
        Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    }
    if ($code -eq 0 -and ([string]$out).Trim() -eq '1') { Write-Host 'select 1: PASS'; return $true }
    Write-Host "select 1: FAIL (exit $code)"
    return $false
}

switch ($Command) {
    'up' {
        Initialize-Credentials
        Invoke-Native { docker compose -f $ComposeFile up -d }
        if ($LASTEXITCODE -ne 0) { throw "docker compose up failed (exit $LASTEXITCODE)" }
        if (-not (Wait-Healthy)) { throw "container did not become healthy: $(Get-ContainerState)" }
        Write-Output "container: $ContainerName $(Get-ContainerState) on 127.0.0.1:5433"
        if (-not (Test-Select1)) { exit 1 }
    }
    'status' {
        if (Test-StoredCredentials) {
            Set-ProcessCredentials
            Write-Output 'credentials: User TEST_DB_* variables set for this setup'
        } else {
            Write-Output 'credentials: User TEST_DB_* variables missing or point elsewhere (run: up)'
        }
        $state = Get-ContainerState
        Write-Output "container: $ContainerName $state"
        Write-Output "volume: $VolumeName $(if (Test-VolumeExists) { 'present' } else { 'absent' })"
        if ($state -eq 'running healthy' -and -not (Test-Select1)) { exit 1 }
    }
    'stop' {
        # Plain stop, never 'compose down -v': the data volume must survive.
        Invoke-Native { docker stop $ContainerName }
        if ($LASTEXITCODE -ne 0) { throw "docker stop failed (exit $LASTEXITCODE)" }
        Write-Output "container: $ContainerName $(Get-ContainerState); volume $VolumeName kept"
    }
}
