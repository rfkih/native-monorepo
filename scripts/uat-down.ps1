# Native UAT — stop the public UAT stack.
#
#   .\scripts\uat-down.ps1         stop containers, KEEP data (tenants, KC users, secrets)
#   .\scripts\uat-down.ps1 -Wipe   stop AND destroy the data volume + docker/uat.env
#
# -Wipe is irreversible: every UAT tenant, all Keycloak users, and the PII/gift-card
# encryption keys are destroyed together (the ciphertexts are useless without the keys,
# which is why the volume and uat.env are wiped as a pair).

[CmdletBinding()]
param([switch]$Wipe)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $repoRoot 'docker\compose.uat.yml'
$envFile = Join-Path $repoRoot 'docker\uat.env'

if (-not (Test-Path $envFile)) {
    # compose interpolation needs an env file; a throwaway is fine for `down`.
    [IO.File]::WriteAllLines($envFile, @('PUBLIC_URL=http://localhost'))
    $envWasMissing = $true
}

$downArgs = @('--env-file', $envFile, '-f', $composeFile, 'down')
if ($Wipe) {
    Write-Warning 'WIPING the UAT stack: all tenants, Keycloak users, and encryption keys will be DESTROYED.'
    $downArgs += '-v'
}

& docker compose @downArgs
if ($LASTEXITCODE -ne 0) { throw "docker compose down failed (exit $LASTEXITCODE)" }

if ($Wipe -or $envWasMissing) {
    Remove-Item $envFile -Force -ErrorAction SilentlyContinue
}
if ($Wipe) {
    Write-Host 'UAT stack wiped (containers, volume, secrets).' -ForegroundColor Yellow
} else {
    Write-Host 'UAT stack stopped. Data kept - restart with .\scripts\uat-up.ps1 -SkipBuild' -ForegroundColor Green
}
