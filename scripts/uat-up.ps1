# Native UAT — bring up (or re-wire) the public UAT stack.
#
#   .\scripts\uat-up.ps1              full run: build jars + images, start, wire, smoke
#   .\scripts\uat-up.ps1 -SkipBuild   restart/re-wire only (e.g. after the tunnel died)
#
# The cloudflared QUICK tunnel URL changes every time the tunnel container is recreated.
# This script discovers the current URL, rewrites docker/uat.env, recreates only the
# containers whose env changed (Keycloak, services, console), re-points the Keycloak
# native-console client at the new origin, registers Debezium connectors idempotently,
# and smoke-tests through the public URL. See docker/uat/README.md.

[CmdletBinding()]
param(
    [switch]$SkipBuild,
    # Build jars in a different checkout (worktree escape hatch, mirrors start-dev-services.ps1).
    [string]$JarRoot,
    # STABLE public URL mode (Tailscale Funnel / named tunnel). When set, the script skips
    # cloudflared + quick-tunnel discovery entirely and wires Keycloak/services to this fixed
    # URL. The URL survives reboots, so this only needs re-running after an image rebuild.
    # e.g. -PublicUrl https://a8.tailbf9662.ts.net:8443
    [string]$PublicUrl
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $JarRoot) { $JarRoot = $repoRoot }
$composeFile = Join-Path $repoRoot 'docker\compose.uat.yml'
$envFile = Join-Path $repoRoot 'docker\uat.env'
$pgVolume = 'native-uat_native-uat-pgdata'

$services = @(
    'gateway', 'org-service', 'restaurant-service', 'carwash-service', 'barbershop-service',
    'loyalty-service', 'finance-service', 'entitlement-service', 'employee-service', 'notification-service',
    'payment-service'
)

function Invoke-Compose {
    param([string[]]$ComposeArgs)
    & docker compose --env-file $envFile -f $composeFile @ComposeArgs
    if ($LASTEXITCODE -ne 0) { throw "docker compose $($ComposeArgs -join ' ') failed (exit $LASTEXITCODE)" }
}

function New-Secret {
    param([int]$Bytes = 32)
    $buf = New-Object byte[] $Bytes
    $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
    $rng.GetBytes($buf)
    $rng.Dispose()
    [Convert]::ToBase64String($buf)
}

function Read-EnvFile {
    $map = @{}
    foreach ($line in [IO.File]::ReadAllLines($envFile)) {
        if ($line -match '^([^=#]+)=(.*)$') { $map[$Matches[1].Trim()] = $Matches[2] }
    }
    $map
}

# ---------------------------------------------------------------- 1. preflight
& docker info *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker is not running.' }

# ---------------------------------------------------------------- 2+3. build
if (-not $SkipBuild) {
    Write-Host '== Building boot jars (10 services) ==' -ForegroundColor Cyan
    $bootJarTasks = $services | ForEach-Object { ":services:${_}:bootJar" }
    Push-Location $JarRoot
    try {
        & .\gradlew.bat @bootJarTasks
        if ($LASTEXITCODE -ne 0) {
            # Known failure mode: corrupt build-logic outputs (CLI + IDE both generating
            # PluginSpecBuilders). The build cache can RESTORE the corrupt artifacts after
            # deletion (FROM-CACHE), so the retry must bypass it.
            Write-Warning 'Gradle build failed - clearing build-logic output and retrying once without the build cache.'
            & .\gradlew.bat --stop
            try { Remove-Item -Recurse -Force (Join-Path $JarRoot 'build-logic\build') -ErrorAction Stop } catch {}
            try { Remove-Item -Recurse -Force (Join-Path $JarRoot 'build-logic\.kotlin') -ErrorAction Stop } catch {}
            & .\gradlew.bat @bootJarTasks --no-build-cache
            if ($LASTEXITCODE -ne 0) { throw 'Gradle bootJar failed twice. See docs/RUNBOOK.md (build in an unwatched worktree, then pass -JarRoot).' }
        }
    } finally { Pop-Location }

    Write-Host '== Building service images (runtime.Dockerfile) ==' -ForegroundColor Cyan
    foreach ($svc in $services) {
        $libs = Join-Path $JarRoot "services\$svc\build\libs"
        # runtime.Dockerfile COPYs *.jar and requires exactly one jar in the context.
        Get-ChildItem (Join-Path $libs '*-plain.jar') -ErrorAction SilentlyContinue | Remove-Item -Force
        $jars = @(Get-ChildItem (Join-Path $libs '*.jar'))
        if ($jars.Count -ne 1) { throw "$svc : expected exactly 1 jar in $libs, found $($jars.Count)" }
        & docker build -q -f (Join-Path $repoRoot 'docker\runtime.Dockerfile') -t "native-uat/${svc}:latest" $libs
        if ($LASTEXITCODE -ne 0) { throw "docker build failed for $svc" }
    }

    Write-Host '== Building console image ==' -ForegroundColor Cyan
    & docker build -t native-uat/console:latest (Join-Path $repoRoot 'frontend\console')
    if ($LASTEXITCODE -ne 0) { throw 'docker build failed for console' }
}

# ---------------------------------------------------------------- 4. secrets
if (-not (Test-Path $envFile)) {
    $volExists = @(& docker volume ls --format '{{.Name}}') -contains $pgVolume
    if ($volExists) {
        throw ("docker/uat.env is MISSING but the data volume '$pgVolume' exists. The PII/gift-card " +
            'ciphertexts and the Keycloak admin password are bound to the lost secrets. Either restore ' +
            'uat.env from backup, or wipe the stack: .\scripts\uat-down.ps1 -Wipe')
    }
    Write-Host '== First run: generating secrets into docker/uat.env (gitignored) ==' -ForegroundColor Cyan
    $lines = @(
        'PUBLIC_URL=http://localhost'          # placeholder until the tunnel URL is known
        "KC_ADMIN_PASSWORD=$(New-Secret 18)"
        "KEYCLOAK_ADMIN_SECRET=$(New-Secret 24)"
        "NATIVE_PII_KEY=$(New-Secret 32)"
        "NATIVE_PII_HMAC_KEY=$(New-Secret 32)"
        "NATIVE_GIFTCARD_CODE_KEY=$(New-Secret 32)"
    )
    [IO.File]::WriteAllLines($envFile, $lines)   # UTF-8 no BOM
}

# ---------------------------------------------------------------- 5. public URL
if ($PublicUrl) {
    # STABLE mode (Tailscale Funnel / named tunnel): no cloudflared, URL is fixed. The
    # funnel/tunnel proxies the public URL to edge (127.0.0.1:8088 -> edge:8080), so we
    # only need edge up here — the full stack comes up at step 7.
    Write-Host "== Stable public-URL mode: $PublicUrl (cloudflared skipped) ==" -ForegroundColor Cyan
    Invoke-Compose @('up', '-d', 'edge')
    $publicUrl = $PublicUrl
} else {
    Write-Host '== Starting edge + cloudflared, discovering the tunnel URL ==' -ForegroundColor Cyan
    # cloudflared is behind the 'quicktunnel' profile — name it AND activate the profile.
    Invoke-Compose @('--profile', 'quicktunnel', 'up', '-d', 'edge', 'cloudflared')

    $hostname = $null
    for ($i = 0; $i -lt 30 -and -not $hostname; $i++) {
        Start-Sleep -Seconds 2
        try {
            $qt = Invoke-RestMethod -Uri 'http://127.0.0.1:12000/quicktunnel' -TimeoutSec 3
            if ($qt.hostname) { $hostname = $qt.hostname }
        } catch {}
    }
    if (-not $hostname) {
        # Fallback: scrape the container log (cloudflared logs to stderr; merge via cmd).
        $log = & cmd /c 'docker logs native-uat-cloudflared 2>&1'
        $m = [regex]::Match(($log -join "`n"), 'https://[a-z0-9-]+\.trycloudflare\.com')
        if ($m.Success) { $hostname = $m.Value -replace '^https://', '' }
    }
    if (-not $hostname) { throw 'Could not discover the quick-tunnel URL (metrics endpoint and log scrape both failed).' }
    $publicUrl = "https://$hostname"
    Write-Host "   public URL: $publicUrl" -ForegroundColor Green
}

# ---------------------------------------------------------------- 6. rewrite uat.env
$envLines = [IO.File]::ReadAllLines($envFile)
$currentUrl = ($envLines | Where-Object { $_ -like 'PUBLIC_URL=*' } | Select-Object -First 1) -replace '^PUBLIC_URL=', ''
if ($currentUrl -ne $publicUrl) {
    $envLines = $envLines | ForEach-Object { if ($_ -like 'PUBLIC_URL=*') { "PUBLIC_URL=$publicUrl" } else { $_ } }
    [IO.File]::WriteAllLines($envFile, $envLines)
    Write-Host '   PUBLIC_URL changed - dependent containers will be recreated.'
}
$env2 = Read-EnvFile

# ---------------------------------------------------------------- 7. full stack
Write-Host '== Starting the full stack (first boot: Flyway + realm import, be patient) ==' -ForegroundColor Cyan
Invoke-Compose @('up', '-d', '--wait', '--wait-timeout', '600')

# ---------------------------------------------------------------- 8. keycloak patch
Write-Host '== Patching Keycloak for the current origin ==' -ForegroundColor Cyan
$kcadm = '/opt/keycloak/bin/kcadm.sh'
$cfg = @('--config', '/tmp/kcadm.config')

function Invoke-Kcadm {
    param([string[]]$KcArgs, [switch]$AllowFail)
    $out = & docker exec native-uat-keycloak $kcadm @KcArgs @cfg
    if ($LASTEXITCODE -ne 0 -and -not $AllowFail) { throw "kcadm $($KcArgs[0..1] -join ' ') failed: $out" }
    $out
}
function Get-KcClientId {
    param([string]$ClientId)
    $json = (Invoke-Kcadm @('get', 'clients', '-r', 'native', '-q', "clientId=$ClientId", '--fields', 'id,clientId')) -join "`n" | ConvertFrom-Json
    $match = @($json) | Where-Object { $_.clientId -eq $ClientId }
    if (-not $match) { throw "Keycloak client '$ClientId' not found" }
    $match[0].id
}

Invoke-Kcadm @('config', 'credentials', '--server', 'http://localhost:8080/auth', '--realm', 'master',
    '--user', 'admin', '--password', $env2['KC_ADMIN_PASSWORD']) | Out-Null

# native-console SPA -> current public origin. The patch travels as a FILE
# (docker cp + kcadm -f): PS 5.1 mangles embedded JSON quotes in native argv, and
# its native-pipe encoding prepends a BOM kcadm rejects — a BOM-less temp file
# sidesteps both. GET-modify-PUT the full client so untouched attributes
# (e.g. pkce.code.challenge.method) survive.
$consoleId = Get-KcClientId 'native-console'
$client = (Invoke-Kcadm @('get', "clients/$consoleId", '-r', 'native')) -join "`n" | ConvertFrom-Json
$client.redirectUris = @("$publicUrl/*")
$client.webOrigins = @($publicUrl)
if ($client.attributes.PSObject.Properties['post.logout.redirect.uris']) {
    $client.attributes.'post.logout.redirect.uris' = "$publicUrl/*"
} else {
    $client.attributes | Add-Member -NotePropertyName 'post.logout.redirect.uris' -NotePropertyValue "$publicUrl/*"
}
$patchFile = Join-Path $env:TEMP 'native-console-client.json'
[IO.File]::WriteAllText($patchFile, ($client | ConvertTo-Json -Depth 16), (New-Object System.Text.UTF8Encoding($false)))
& docker cp $patchFile native-uat-keycloak:/tmp/native-console-client.json
if ($LASTEXITCODE -ne 0) { throw 'docker cp of the client patch failed' }
Remove-Item $patchFile -Force
Invoke-Kcadm @('update', "clients/$consoleId", '-r', 'native', '-f', '/tmp/native-console-client.json') | Out-Null

# Rotate the committed native-admin secret to the generated one (org-service uses it).
$adminId = Get-KcClientId 'native-admin'
Invoke-Kcadm @('update', "clients/$adminId", '-r', 'native', '-s', "secret=$($env2['KEYCLOAK_ADMIN_SECRET'])") | Out-Null

# native-gateway: direct-access grants + committed secret = public password oracle. Unused at runtime.
$gwId = Get-KcClientId 'native-gateway'
Invoke-Kcadm @('update', "clients/$gwId", '-r', 'native', '-s', 'enabled=false') | Out-Null

# Committed seed users are weak public credentials - UAT is signup-only.
foreach ($u in @('owner-acme', 'cashier-acme')) {
    $json = (Invoke-Kcadm @('get', 'users', '-r', 'native', '-q', "username=$u", '--fields', 'id,username')) -join "`n" | ConvertFrom-Json
    $hit = @($json) | Where-Object { $_.username -eq $u }
    if ($hit) { Invoke-Kcadm @('update', "users/$($hit[0].id)", '-r', 'native', '-s', 'enabled=false') | Out-Null }
}

Invoke-Kcadm @('update', 'realms/native', '-s', 'bruteForceProtected=true') | Out-Null
Write-Host '   redirect URIs re-pointed; native-gateway disabled; native-admin secret rotated; seed users disabled.'

# ---------------------------------------------------------------- 9. debezium
Write-Host '== Registering Debezium outbox connectors (idempotent PUT) ==' -ForegroundColor Cyan
$connectorFiles = Get-ChildItem (Join-Path $repoRoot 'docker\debezium\*.json')
foreach ($f in $connectorFiles) {
    $j = Get-Content -Raw $f.FullName | ConvertFrom-Json
    # Body as UTF-8 BYTES: PS 5.1 encodes string bodies as Latin-1, corrupting any
    # non-ASCII character in the connector config.
    $body = [System.Text.Encoding]::UTF8.GetBytes(($j.config | ConvertTo-Json -Depth 8))
    Invoke-RestMethod -Method Put -Uri "http://127.0.0.1:18093/connectors/$($j.name)/config" `
        -ContentType 'application/json; charset=utf-8' -Body $body | Out-Null
}
foreach ($f in $connectorFiles) {
    $name = (Get-Content -Raw $f.FullName | ConvertFrom-Json).name
    $state = ''
    for ($i = 0; $i -lt 15 -and $state -ne 'RUNNING'; $i++) {
        Start-Sleep -Seconds 2
        try {
            $status = Invoke-RestMethod -Uri "http://127.0.0.1:18093/connectors/$name/status"
            $state = $status.connector.state
            # A task can fail transiently (e.g. it raced a postgres restart) while the
            # connector shows RUNNING - restart failed tasks and keep polling.
            foreach ($t in @($status.tasks | Where-Object { $_.state -eq 'FAILED' })) {
                $state = 'TASK_FAILED'
                Invoke-RestMethod -Method Post -ContentType 'application/json' `
                    -Uri "http://127.0.0.1:18093/connectors/$name/tasks/$($t.id)/restart" | Out-Null
            }
        } catch {}
    }
    if ($state -ne 'RUNNING') { Write-Warning "connector $name is '$state' (expected RUNNING) - check http://127.0.0.1:18093/connectors/$name/status" }
}

# ---------------------------------------------------------------- 10. smoke
Write-Host '== Smoke tests through the public URL ==' -ForegroundColor Cyan
$smokeFailures = @()

$envjs = $null
for ($i = 0; $i -lt 10 -and -not $envjs; $i++) {
    try { $envjs = (Invoke-WebRequest -Uri "$publicUrl/env.js" -UseBasicParsing -TimeoutSec 10).Content } catch { Start-Sleep -Seconds 3 }
}
if (-not $envjs -or $envjs -notlike "*$publicUrl/auth*") { $smokeFailures += 'env.js does not carry the public Keycloak URL' }

try {
    $oidc = Invoke-RestMethod -Uri "$publicUrl/auth/realms/native/.well-known/openid-configuration" -TimeoutSec 10
    if ($oidc.issuer -ne "$publicUrl/auth/realms/native") { $smokeFailures += "issuer mismatch: $($oidc.issuer)" }
} catch { $smokeFailures += "OIDC discovery failed: $($_.Exception.Message)" }

try {
    Invoke-WebRequest -Uri "$publicUrl/api/v1/companies/mine" -UseBasicParsing -TimeoutSec 10 | Out-Null
    $smokeFailures += '/api/v1/companies/mine returned success without a token (expected 401)'
} catch {
    if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -ne 401) { $smokeFailures += "/api unauthenticated returned $([int]$_.Exception.Response.StatusCode) (expected 401)" }
}

try {
    Invoke-WebRequest -Uri "$publicUrl/auth/admin/" -UseBasicParsing -TimeoutSec 10 | Out-Null
    $smokeFailures += '/auth/admin/ is publicly reachable (expected 404)'
} catch {
    if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -ne 404) { $smokeFailures += "/auth/admin/ returned $([int]$_.Exception.Response.StatusCode) (expected 404)" }
}

if ($smokeFailures) {
    $smokeFailures | ForEach-Object { Write-Warning "SMOKE: $_" }
    throw "Smoke tests failed ($($smokeFailures.Count))."
}

Write-Host ''
Write-Host '=====================================================================' -ForegroundColor Green
Write-Host " UAT stack is UP:  $publicUrl" -ForegroundColor Green
Write-Host '=====================================================================' -ForegroundColor Green
Write-Host ' Testers : open the URL, Sign up to create a tenant (signup-only UAT).'
Write-Host " Operator: KC admin  http://localhost:18090/auth/admin/ (admin / see docker/uat.env)"
Write-Host '           Connect   http://127.0.0.1:18093/connectors'
if ($PublicUrl) {
    Write-Host '           Stable Funnel URL — survives reboots. After an image rebuild, re-run:'
    Write-Host "           .\scripts\uat-up.ps1 -SkipBuild -PublicUrl $publicUrl"
} else {
    Write-Host '           Quick tunnel died? Re-run: .\scripts\uat-up.ps1 -SkipBuild (URL will change)'
}
