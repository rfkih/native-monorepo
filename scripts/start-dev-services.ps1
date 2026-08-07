# start-dev-services.ps1 - bring up every host-run Native service that isn't already listening.
#
# The dev stack runs infra in docker (postgres 15432, kafka 19092, keycloak 18080, connect 18083)
# but the Java services run ON THE HOST (docs/RUNBOOK.md) - so a reboot or a closed terminal
# silently kills them, and the console then shows bodyless 500s/502s for whatever died. This
# script makes recovery one command: it checks each port, launches only what's missing (detached,
# hidden window), waits for /actuator/health, and prints a status table.
#
#   .\scripts\start-dev-services.ps1                      # use jars under the repo
#   .\scripts\start-dev-services.ps1 -JarRoot C:\some-wt  # use jars built in a worktree
#   .\scripts\start-dev-services.ps1 -Only finance-service,gateway
#
# Jars must already be built (.\gradlew :services:<name>:bootJar). If the main checkout's
# build-logic is poisoned (the VS Code gotcha in docs/RUNBOOK.md), build in a git worktree and
# pass -JarRoot. Logs land in %TEMP%\native-services\<name>.log.

param(
  [string]$JarRoot = (Split-Path -Parent $PSScriptRoot),
  [string[]]$Only = @()
)

$ErrorActionPreference = 'Stop'

$services = @(
  @{ Name = 'org-service';         Port = 8082; Db = 'org_service' },
  @{ Name = 'employee-service';    Port = 8084; Db = 'employee_service' },
  @{ Name = 'finance-service';     Port = 8085; Db = 'finance_service' },
  @{ Name = 'restaurant-service';  Port = 8086; Db = 'restaurant_service' },
  @{ Name = 'carwash-service';     Port = 8087; Db = 'carwash_service' },
  @{ Name = 'barbershop-service';  Port = 8088; Db = 'barbershop_service' },
  @{ Name = 'entitlement-service'; Port = 8089; Db = 'entitlement_service' },
  @{ Name = 'payment-service';     Port = 8091; Db = 'payment_service' },
  @{ Name = 'gateway';             Port = 8090; Db = $null }
)

$java = (Get-ChildItem "$env:USERPROFILE\.gradle\jdks\*25*\bin\java.exe" | Select-Object -First 1).FullName
if (-not $java) { throw "No Java 25 toolchain under ~\.gradle\jdks - run any .\gradlew build once first." }

$logDir = Join-Path $env:TEMP 'native-services'
New-Item -ItemType Directory -Force $logDir | Out-Null

function Test-PortListening([int]$port) {
  return [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

$started = @()
foreach ($svc in $services) {
  if ($Only.Count -gt 0 -and $Only -notcontains $svc.Name) { continue }
  if (Test-PortListening $svc.Port) {
    Write-Host ("{0,-22} :{1}  already listening - skipped" -f $svc.Name, $svc.Port)
    continue
  }

  $jar = Join-Path $JarRoot "services\$($svc.Name)\build\libs\$($svc.Name)-0.1.0-SNAPSHOT.jar"
  if (-not (Test-Path $jar)) {
    Write-Host ("{0,-22} :{1}  NO JAR at {2} - build it first" -f $svc.Name, $svc.Port, $jar)
    continue
  }

  # Env is inherited by the child: shared dev settings + per-service DB. The gateway instead
  # gets Keycloak issuer/JWKS and the localhost *_SERVICE_URI overrides (its yml defaults are
  # docker-compose hostnames, which do not resolve on the host).
  $env:SPRING_PROFILES_ACTIVE = 'dev'
  $env:SERVER_PORT = "$($svc.Port)"
  $env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:19092'
  $env:NATIVE_DEV_TENANT_FILTER_ENABLED = 'true'
  $env:KEYCLOAK_BASE_URL = 'http://localhost:18080'
  if ($svc.Db) {
    $env:DB_URL = "jdbc:postgresql://localhost:15432/$($svc.Db)"
    $env:DB_USERNAME = $svc.Db; $env:DB_PASSWORD = $svc.Db
  } else {
    Remove-Item Env:DB_URL, Env:DB_USERNAME, Env:DB_PASSWORD -ErrorAction SilentlyContinue
    $env:KEYCLOAK_ISSUER_URI = 'http://localhost:18080/realms/native'
    $env:KEYCLOAK_JWK_SET_URI = 'http://localhost:18080/realms/native/protocol/openid-connect/certs'
    $env:ORG_SERVICE_URI = 'http://localhost:8082'
    $env:EMPLOYEE_SERVICE_URI = 'http://localhost:8084'
    $env:FINANCE_SERVICE_URI = 'http://localhost:8085'
    $env:RESTAURANT_SERVICE_URI = 'http://localhost:8086'
    $env:CARWASH_SERVICE_URI = 'http://localhost:8087'
    $env:BARBERSHOP_SERVICE_URI = 'http://localhost:8088'
    $env:ENTITLEMENT_SERVICE_URI = 'http://localhost:8089'
    $env:PAYMENT_SERVICE_URI = 'http://localhost:8091'
  }

  Start-Process -FilePath $java -ArgumentList '-jar', "`"$jar`"" -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logDir "$($svc.Name).log") `
    -RedirectStandardError (Join-Path $logDir "$($svc.Name).err.log")
  Write-Host ("{0,-22} :{1}  launching from {2}" -f $svc.Name, $svc.Port, $jar)
  $started += $svc
}

# Clean the inherited env out of THIS shell so later commands in the same session aren't surprised.
Remove-Item Env:SPRING_PROFILES_ACTIVE, Env:SERVER_PORT, Env:KAFKA_BOOTSTRAP_SERVERS,
  Env:NATIVE_DEV_TENANT_FILTER_ENABLED, Env:KEYCLOAK_BASE_URL, Env:DB_URL, Env:DB_USERNAME,
  Env:DB_PASSWORD, Env:KEYCLOAK_ISSUER_URI, Env:KEYCLOAK_JWK_SET_URI, Env:ORG_SERVICE_URI,
  Env:EMPLOYEE_SERVICE_URI, Env:FINANCE_SERVICE_URI, Env:RESTAURANT_SERVICE_URI,
  Env:CARWASH_SERVICE_URI, Env:BARBERSHOP_SERVICE_URI, Env:ENTITLEMENT_SERVICE_URI,
  Env:PAYMENT_SERVICE_URI `
  -ErrorAction SilentlyContinue

if ($started.Count -eq 0) { Write-Host 'Nothing to start.'; exit 0 }

# Wait for health. The gateway secures /actuator/health (401) - any HTTP answer counts as alive.
Write-Host "`nWaiting for health..."
$deadline = (Get-Date).AddSeconds(90)
$pending = [System.Collections.ArrayList]@($started)
while ($pending.Count -gt 0 -and (Get-Date) -lt $deadline) {
  Start-Sleep -Seconds 2
  foreach ($svc in @($pending)) {
    # PS 5.1-safe (no -SkipHttpErrorCheck): a 4xx throws a WebException that still CARRIES the
    # response - the gateway secures health (401), and any HTTP answer means the process is up.
    try {
      $code = [int](Invoke-WebRequest -Uri "http://localhost:$($svc.Port)/actuator/health" -Method GET -TimeoutSec 2 -UseBasicParsing).StatusCode
    } catch [System.Net.WebException] {
      if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode } else { $code = 0 }
    } catch { $code = 0 }
    if ($code -ne 0) {
      Write-Host ("{0,-22} :{1}  UP ({2})" -f $svc.Name, $svc.Port, $code)
      $pending.Remove($svc)
    }
  }
}
foreach ($svc in $pending) {
  Write-Host ("{0,-22} :{1}  STILL NOT UP - check {2}\{3}.err.log" -f $svc.Name, $svc.Port, $logDir, $svc.Name)
}
