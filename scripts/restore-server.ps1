param(
    [Parameter(Mandatory = $true)][string]$BundleDirectory,
    [Parameter(Mandatory = $true)][string]$ServerDirectory,
    [Parameter(Mandatory = $true)][string]$PostgresBin,
    [Parameter(Mandatory = $true)][string]$HostName,
    [Parameter(Mandatory = $true)][int]$Port,
    [Parameter(Mandatory = $true)][string]$DatabaseName,
    [Parameter(Mandatory = $true)][string]$UserName
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# Restore vào DB và thư mục mới; không xóa hoặc ghi đè phiên đang vận hành.
[string]$bundle = (Resolve-Path -LiteralPath $BundleDirectory).Path
[string]$destination = [IO.Path]::GetFullPath($ServerDirectory)
if (Test-Path -LiteralPath $destination) { throw "Đích restore đã tồn tại: $destination" }
if ($destination.StartsWith($bundle + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Đích server phải nằm ngoài bundle.' }
[object]$manifest = Get-Content -LiteralPath (Join-Path $bundle 'manifest.json') -Raw | ConvertFrom-Json
if ($manifest.format -ne 1) { throw 'Định dạng manifest không được hỗ trợ.' }
if ($DatabaseName -eq $manifest.database) { throw 'Dùng tên DB mới để tránh ghi đè DB gốc.' }
foreach ($entry in $manifest.files) {
    [string]$path = [IO.Path]::GetFullPath((Join-Path $bundle $entry.path))
    if (-not $path.StartsWith($bundle + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw "Đường dẫn ngoài bundle: $($entry.path)" }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) { throw "Checksum sai: $($entry.path)" }
}
[string[]]$actual = @(Get-ChildItem -LiteralPath $bundle -File -Recurse | Where-Object { $_.FullName -ne (Join-Path $bundle 'manifest.json') } | ForEach-Object { [IO.Path]::GetRelativePath($bundle, $_.FullName) } | Sort-Object)
[string[]]$expected = @($manifest.files.path | Sort-Object)
if (@(Compare-Object $actual $expected).Count -ne 0) { throw 'Danh sách file khác manifest; không restore bundle bị thêm/bớt file.' }
[string]$newJdbc = "jdbc:postgresql://${HostName}:${Port}/$DatabaseName"
[string]$configRelative = 'plugins/CuuVucCore/config.yml'
[string]$config = Get-Content -LiteralPath (Join-Path $bundle "server/$configRelative") -Raw
if (-not $config.Contains([string]$manifest.jdbcUrl)) { throw 'URL DB trong config không khớp manifest; kiểm tra cấu hình bundle.' }
[string]$createdb = Join-Path $PostgresBin 'createdb.exe'
[string]$restore = Join-Path $PostgresBin 'pg_restore.exe'
& $createdb --no-password --host=$HostName --port=$Port --username=$UserName $DatabaseName
if ($LASTEXITCODE -ne 0) { throw "Không tạo được DB mới $DatabaseName : exit=$LASTEXITCODE" }
& $restore --no-password --host=$HostName --port=$Port --username=$UserName --dbname=$DatabaseName --exit-on-error (Join-Path $bundle 'database.dump')
if ($LASTEXITCODE -ne 0) { throw "pg_restore thất bại: exit=$LASTEXITCODE. Không khởi động server; kiểm tra DB mới $DatabaseName." }
Copy-Item -LiteralPath (Join-Path $bundle 'server') -Destination $destination -Recurse
Set-Content -LiteralPath (Join-Path $destination $configRelative) -Value $config.Replace([string]$manifest.jdbcUrl, $newJdbc) -Encoding utf8
Write-Output "RESTORE_COMPLETE files=$($actual.Count) database=$DatabaseName server=$destination"
