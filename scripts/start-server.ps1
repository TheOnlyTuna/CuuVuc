param([Parameter(Mandatory = $true)][string]$JavaHome)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$java = Join-Path $JavaHome 'bin/java.exe'
$runPath = Join-Path $projectRoot 'run'
if (-not (Test-Path -LiteralPath $java)) { throw "Không tìm thấy Java: $java" }
if (-not (Test-Path -LiteralPath (Join-Path $runPath 'paper.jar'))) { throw 'Chạy scripts/prepare-server.ps1 trước.' }
if (-not (Select-String -LiteralPath (Join-Path $runPath 'eula.txt') -Pattern '^eula=true$' -Quiet)) {
    throw 'Đọc https://aka.ms/MinecraftEULA và tự đặt eula=true nếu đồng ý.'
}
if ([string]::IsNullOrWhiteSpace($env:CUUVUC_DB_PASSWORD)) { throw 'Chưa đặt CUUVUC_DB_PASSWORD.' }
Push-Location $runPath
try {
    & $java -Xms512M -Xmx1536M -jar paper.jar --nogui
    if ($LASTEXITCODE -ne 0) { throw "Paper dừng với exit code $LASTEXITCODE. Xem run/logs/latest.log." }
} finally { Pop-Location }
