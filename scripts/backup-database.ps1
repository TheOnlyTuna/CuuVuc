param(
    [Parameter(Mandatory = $true)][string]$PostgresBin,
    [Parameter(Mandatory = $true)][string]$HostName,
    [Parameter(Mandatory = $true)][int]$Port,
    [Parameter(Mandatory = $true)][string]$DatabaseName,
    [Parameter(Mandatory = $true)][string]$UserName
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[string]$projectRoot = Split-Path $PSScriptRoot -Parent
[string]$backupPath = Join-Path $projectRoot 'backups'
[string]$dump = Join-Path $PostgresBin 'pg_dump.exe'
if (-not (Test-Path -LiteralPath $dump)) { throw "Thiếu pg_dump: $dump" }
New-Item -ItemType Directory -Force $backupPath | Out-Null
[string]$output = Join-Path $backupPath ('cuuvuc-' + (Get-Date -Format 'yyyyMMddHHmmssfff') + '.dump')
& $dump --no-password --format=custom --host=$HostName --port=$Port --username=$UserName --dbname=$DatabaseName --file=$output
if ($LASTEXITCODE -ne 0) { throw "Backup thất bại, exit code $LASTEXITCODE. Kiểm tra biến PGPASSWORD hoặc pgpass." }
Get-FileHash -LiteralPath $output -Algorithm SHA256
Write-Output $output
