Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$runPath = Join-Path $projectRoot 'run'
$pluginPath = Join-Path $runPath 'plugins/CuuVucCore'
$artifact = Join-Path $projectRoot 'target/CuuVucCore-0.6.0.jar'
if (-not (Test-Path -LiteralPath $artifact)) { throw 'Build trước: ./mvnw.cmd clean package' }
New-Item -ItemType Directory -Force $pluginPath | Out-Null
$oldPlugins = @(Get-ChildItem -LiteralPath (Join-Path $runPath 'plugins') -Filter 'CuuVucCore-*.jar' -File | Where-Object Name -ne 'CuuVucCore-0.6.0.jar')
if ($oldPlugins.Count -gt 0) {
    $backupPath = Join-Path $runPath 'plugin-backups'
    New-Item -ItemType Directory -Force $backupPath | Out-Null
    foreach ($oldPlugin in $oldPlugins) {
        $destination = Join-Path $backupPath ($oldPlugin.BaseName + '-' + (Get-Date -Format 'yyyyMMddHHmmssfff') + '.jar')
        Move-Item -LiteralPath $oldPlugin.FullName -Destination $destination
    }
}
$serverJar = Join-Path $runPath 'paper.jar'
$expectedHash = 'dbaae37e10e30e44ad1ff65cf00992240c3e79d33e150f9c4e833ed0338e8ccd'
if (-not (Test-Path -LiteralPath $serverJar)) {
    $url = "https://fill-data.papermc.io/v1/objects/$expectedHash/paper-26.3-166.jar"
    Invoke-WebRequest -Uri $url -OutFile $serverJar -MaximumRetryCount 3 -RetryIntervalSec 2
}
if ((Get-FileHash -LiteralPath $serverJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) {
    throw 'Checksum Paper không khớp build 166. Kiểm tra run/paper.jar trước khi chạy.'
}
Copy-Item -LiteralPath $artifact -Destination (Join-Path $runPath 'plugins/CuuVucCore-0.6.0.jar') -Force
$configPath = Join-Path $pluginPath 'config.yml'
if (-not (Test-Path -LiteralPath $configPath)) {
    Copy-Item -LiteralPath (Join-Path $projectRoot 'src/main/resources/config.yml') -Destination $configPath
}
$propertiesPath = Join-Path $runPath 'server.properties'
if (-not (Test-Path -LiteralPath $propertiesPath)) {
    @'
server-ip=127.0.0.1
server-port=25579
online-mode=true
level-type=minecraft:flat
generate-structures=false
spawn-protection=0
view-distance=4
simulation-distance=4
max-players=8
pause-when-empty-seconds=-1
'@ | Set-Content -LiteralPath $propertiesPath -Encoding utf8
}
$eulaPath = Join-Path $runPath 'eula.txt'
if (-not (Test-Path -LiteralPath $eulaPath)) { Set-Content -LiteralPath $eulaPath -Value 'eula=false' }
Write-Output 'Đã chuẩn bị Paper 26.3 build 166. Đọc EULA, cấu hình DB, rồi chạy scripts/start-server.ps1.'
