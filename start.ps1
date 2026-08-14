[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [int]$HealthTimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = $PSScriptRoot
$targetProfile = Join-Path $projectRoot 'target-profile.yaml'
$sampleTargetProfile = Join-Path $projectRoot 'target-profile.sample.yaml'

Set-Location $projectRoot

try {
    $dockerVersion = docker info --format '{{.ServerVersion}}'
} catch {
    throw 'Docker Desktop is not ready. Start Docker Desktop, wait for the engine to be running, then run .\start.ps1 again.'
}

if (Test-Path -LiteralPath $targetProfile) {
    $env:ARL_TARGET_PROFILE = (Resolve-Path -LiteralPath $targetProfile).Path
    Write-Host "Using Target Profile: $env:ARL_TARGET_PROFILE"
} else {
    $env:ARL_TARGET_PROFILE = (Resolve-Path -LiteralPath $sampleTargetProfile).Path
    Write-Warning 'target-profile.yaml was not found. ARL will start with the safe sample Profile; create target-profile.yaml before testing a real Target.'
}

if ([string]::IsNullOrWhiteSpace($env:ARL_OLLAMA_BASE_URL)) {
    $env:ARL_OLLAMA_BASE_URL = 'http://host.docker.internal:11434'
}

try {
    Invoke-RestMethod -Uri "$($env:ARL_OLLAMA_BASE_URL)/api/tags" -TimeoutSec 3 | Out-Null
    Write-Host "Ollama is reachable at $env:ARL_OLLAMA_BASE_URL"
} catch {
    Write-Warning "Ollama is not reachable at $env:ARL_OLLAMA_BASE_URL. ARL will start, but AI analysis requests will remain MODEL_UNAVAILABLE until Ollama is available."
}

Write-Host "Docker Engine: $dockerVersion"
$composeArguments = @('compose', '--profile', 'arl', 'up', '--detach', '--force-recreate')
if (-not $SkipBuild) {
    $composeArguments += '--build'
}

& docker @composeArguments
if ($LASTEXITCODE -ne 0) {
    throw 'ARL containers could not be started.'
}

$deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
do {
    try {
        $health = Invoke-RestMethod -Uri 'http://localhost:8090/actuator/health' -TimeoutSec 2
        if ($health.status -eq 'UP') {
            Write-Host 'ARL Workbench is ready: http://localhost:8090'
            Write-Host 'Health check: http://localhost:8090/actuator/health'
            Write-Host 'Open the Workbench, import or select a Target Profile, then choose candidates and approve a Batch.'
            exit 0
        }
    } catch {
        # The application may still be applying Flyway migrations or waiting for PostgreSQL.
    }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)

& docker compose logs --tail 100 arl
throw "ARL did not become healthy within $HealthTimeoutSeconds seconds. The last ARL container logs are shown above."
