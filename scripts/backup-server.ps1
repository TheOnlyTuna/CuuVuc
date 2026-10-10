param(
    [Parameter(Mandatory = $true)][string]$ServerDirectory,
    [Parameter(Mandatory = $true)][string]$PostgresBin,
    [Parameter(Mandatory = $true)][string]$HostName,
    [Parameter(Mandatory = $true)][int]$Port,
    [Parameter(Mandatory = $true)][string]$DatabaseName,
    [Parameter(Mandatory = $true)][string]$UserName,
    [Parameter(Mandatory = $true)][string]$OutputDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# Chỉ backup khi đã stop/drain; không xem save-all là ranh giới nhất quán DB/world.
[string]$server = (Resolve-Path -LiteralPath $ServerDirectory).Path
[string]$destination = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $destination) { throw "Thư mục backup đã tồn tại: $destination" }
if ($destination.StartsWith($server + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or $destination -eq $server) { throw 'Backup phải nằm ngoài thư mục server.' }
[string]$log = Get-Content -LiteralPath (Join-Path $server 'logs/latest.log') -Raw
[string]$jdbcUrl = "jdbc:postgresql://${HostName}:${Port}/$DatabaseName"
[string]$config = Get-Content -LiteralPath (Join-Path $server 'plugins/CuuVucCore/config.yml') -Raw
if (-not $config.Contains($jdbcUrl)) { throw 'URL DB trong config không khớp DB cần backup.' }
if ($log -notmatch 'database_drain_complete' -or $log -notmatch '(?m)^\[[^\r\n]+\]: All RegionFile I/O tasks to complete\r?$') { throw 'Thiếu bằng chứng stop/drain/world I/O trong latest.log. Gõ stop và chờ process kết thúc.' }
[string[]]$locks = @(Get-ChildItem -LiteralPath $server -Filter session.lock -File -Recurse | Select-Object -ExpandProperty FullName)
if ($locks.Count -eq 0) { throw 'Không tìm thấy session.lock của world; kiểm tra đường dẫn server.' }
[Collections.Generic.List[IO.FileStream]]$heldLocks = [Collections.Generic.List[IO.FileStream]]::new()
try {
    foreach ($lockPath in $locks) {
        # Cho phép copy file khóa nhưng ngăn Paper mở world để ghi trong lúc backup.
        $heldLocks.Add([IO.File]::Open($lockPath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::Read))
    }
    [string]$psql = Join-Path $PostgresBin 'psql.exe'
    [string]$dump = Join-Path $PostgresBin 'pg_dump.exe'
    [string]$sessions = & $psql --no-password --host=$HostName --port=$Port --username=$UserName --dbname=$DatabaseName -At -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND backend_type='client backend';"
    if ($LASTEXITCODE -ne 0) { throw "Không kiểm tra được kết nối DB: exit=$LASTEXITCODE" }
    if ($sessions.Trim() -ne '0') { throw "DB còn $sessions kết nối khác; đóng mọi writer trước khi backup." }
    New-Item -ItemType Directory -Path $destination | Out-Null
    Copy-Item -LiteralPath $server -Destination (Join-Path $destination 'server') -Recurse
    [string]$dumpFile = Join-Path $destination 'database.dump'
    & $dump --no-password --host=$HostName --port=$Port --username=$UserName --dbname=$DatabaseName --format=custom --file=$dumpFile
    if ($LASTEXITCODE -ne 0) { throw "pg_dump thất bại: exit=$LASTEXITCODE. Bundle chưa có manifest hợp lệ." }
    [object[]]$files = @(Get-ChildItem -LiteralPath $destination -File -Recurse | Sort-Object FullName | ForEach-Object {
        [pscustomobject]@{ path = [IO.Path]::GetRelativePath($destination, $_.FullName); sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
    [pscustomobject]@{ format = 1; createdUtc = [DateTime]::UtcNow.ToString('o'); database = $DatabaseName; jdbcUrl = $jdbcUrl; files = $files } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $destination 'manifest.json') -Encoding utf8
    Write-Output "BACKUP_COMPLETE files=$($files.Count) bundle=$destination"
} finally {
    foreach ($heldLock in $heldLocks) { $heldLock.Dispose() }
}
