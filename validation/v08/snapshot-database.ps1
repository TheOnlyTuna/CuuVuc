param(
    [Parameter(Mandatory = $true)][string]$PostgresBin,
    [Parameter(Mandatory = $true)][string]$HostName,
    [Parameter(Mandatory = $true)][int]$Port,
    [Parameter(Mandatory = $true)][string]$DatabaseName,
    [Parameter(Mandatory = $true)][string]$UserName,
    [Parameter(Mandatory = $true)][string]$OutputFile
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# Chụp dữ liệu schema public theo thứ tự cố định; caller phải dừng mọi writer trước đối soát restore.
[string]$psql = Join-Path $PostgresBin 'psql.exe'
[string[]]$tables = @(& $psql -h $HostName -p $Port -U $UserName -d $DatabaseName -X -qAt -v ON_ERROR_STOP=1 -c "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename")
if ($LASTEXITCODE -ne 0) { throw "Không đọc được bảng DB=$DatabaseName, host=$HostName, port=$Port" }
[string[]]$queries = @('BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;', "SET LOCAL TIME ZONE 'UTC';")
foreach ($table in $tables) {
    if ($table -notmatch '^[a-z][a-z0-9_]*$') { throw "Tên bảng không hợp lệ: $table" }
    $queries += 'SELECT jsonb_build_object(''table'',''{0}'',''rows'',coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text COLLATE "C"),''[]''::jsonb)) FROM public.{0} t;' -f $table
}
$queries += 'COMMIT;'
[string[]]$rows = @(($queries -join "`n") | & $psql -h $HostName -p $Port -U $UserName -d $DatabaseName -X -qAt -v ON_ERROR_STOP=1)
if ($LASTEXITCODE -ne 0) { throw "Snapshot DB thất bại: DB=$DatabaseName, host=$HostName, port=$Port" }
if ($rows.Count -ne $tables.Count) { throw "Số dòng snapshot không khớp số bảng DB=$DatabaseName" }
[IO.File]::WriteAllText([IO.Path]::GetFullPath($OutputFile), ($rows -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
Write-Output "Snapshot: $($tables.Count) bảng, DB=$DatabaseName"
