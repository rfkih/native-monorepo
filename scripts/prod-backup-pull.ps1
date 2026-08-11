# Native PROD offsite backup pull — runs ON THE OWNER'S MACHINE (a8) via Windows Task Scheduler
# (ADR 0053 §5: encrypted offsite copy; ADR 0057 Phase 6). Registered as task
# "NativeProdBackupPull" (daily 04:00 WIB, ~2h after the VPS nightly cron at 02:10 WIB).
#
# Pulls any nightly archive not yet present locally from the VPS over Tailscale, verifies the
# transfer by size, and keeps the newest 30 locally. The archives are AES-256 encrypted on the
# VPS; the passphrase lives in %USERPROFILE%\.native-prod-backup-passphrase.txt on this machine
# (and should ALSO be in a password manager) — losing BOTH the VPS and the passphrase means
# losing the backups.
[CmdletBinding()]
param(
    [string]$Dest = "$env:USERPROFILE\native-prod-backups",
    [string]$SshKey = 'C:\Project\sshkey.pem',
    [string]$VpsHost = '100.112.13.126',   # Tailscale IP (falls back to public if tailnet is down)
    [string]$VpsUser = 'starsky',
    [int]$Keep = 30
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $Dest | Out-Null
$log = Join-Path $Dest 'pull.log'
function Log([string]$m) { $line = "[{0:u}] {1}" -f (Get-Date).ToUniversalTime(), $m; $line | Tee-Object -FilePath $log -Append }

$sshBase = @('-i', $SshKey, '-o', 'IdentitiesOnly=yes', '-o', 'ConnectTimeout=20')
$remote = "$VpsUser@$VpsHost"

# List remote nightly archives as name:size. Deliberately quote-free: Windows ssh.exe argument
# re-quoting strips embedded double quotes (a "%n|%s" format arrives unquoted and the | becomes
# a remote PIPE). ':' never appears in the fixed archive names.
$listing = & ssh @sshBase $remote 'cd ~/native-prod/backups/nightly 2>/dev/null && stat -c %n:%s native-*.tar.gz.enc 2>/dev/null'
if ($LASTEXITCODE -ne 0 -or -not $listing) { Log 'ERROR: could not list remote backups'; exit 1 }

$pulled = 0
foreach ($line in @($listing)) {
    $name, $size = $line -split ':'
    $local = Join-Path $Dest $name
    if ((Test-Path $local) -and ((Get-Item $local).Length -eq [long]$size)) { continue }
    & scp @sshBase "${remote}:~/native-prod/backups/nightly/$name" $local | Out-Null
    if ($LASTEXITCODE -ne 0 -or ((Get-Item $local -ErrorAction SilentlyContinue).Length -ne [long]$size)) {
        Log "ERROR: pull of $name failed/size-mismatch"; exit 1
    }
    Log "pulled $name ($([math]::Round([long]$size/1MB,1)) MB)"
    $pulled++
}

# Local retention: newest $Keep.
Get-ChildItem $Dest -Filter 'native-*.tar.gz.enc' | Sort-Object Name -Descending |
    Select-Object -Skip $Keep | ForEach-Object { Log "retention: removing $($_.Name)"; Remove-Item $_.FullName -Force -Confirm:$false }

Log "pull OK ($pulled new; $((Get-ChildItem $Dest -Filter 'native-*.tar.gz.enc').Count) retained)"
