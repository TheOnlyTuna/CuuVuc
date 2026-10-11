param(
    [Parameter(Mandatory=$true)][string]$RuntimeDirectory,
    [Parameter(Mandatory=$true)][string]$DatabaseName,
    [Parameter(Mandatory=$true)][int]$ServerProcessId,
    [Parameter(Mandatory=$true)][string]$PostgresBin,
    [Parameter(Mandatory=$true)][string]$OutputDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
[string]$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
[string]$runtime=(Resolve-Path -LiteralPath $RuntimeDirectory).Path
if (-not $runtime.StartsWith($repo+'\run-b01-', [StringComparison]::OrdinalIgnoreCase) -or $DatabaseName -notmatch '^cv_b01_client_[a-z0-9_]+$') { throw 'Không phải runtime/DB client thử riêng B01' }
[DateTime]$deadline=[DateTime]::UtcNow.AddMinutes(3)
while ([DateTime]::UtcNow -lt $deadline) {
    [string[]]$rows=@(& (Join-Path $PostgresBin 'psql.exe') -X -h 127.0.0.1 -p 55439 -U cuuvuc_test -d $DatabaseName -At -v ON_ERROR_STOP=1 -c "SELECT json_build_object('backend_pid',pid,'wait',wait_event,'query',query)::text FROM pg_stat_activity WHERE datname=current_database() AND wait_event='PgSleep' AND query LIKE 'UPDATE operations SET result=%'")
    if ($LASTEXITCODE -ne 0) { throw 'Không đọc được marker transaction; không kill mù' }
    if ($rows.Count -gt 0) {
        [object]$actual=Get-CimInstance Win32_Process -Filter "ProcessId=$ServerProcessId"
        if ($null -eq $actual -or -not $actual.CommandLine.Contains((Join-Path $runtime 'paper.jar'))) { throw 'PID không khớp Paper runtime thử' }
        Set-Content (Join-Path $OutputDirectory 'native-precommit-marker.json') ($rows -join "`n") -Encoding utf8
        Stop-Process -Id $ServerProcessId -Force
        Add-Content (Join-Path $OutputDirectory 'kills.txt') "NATIVE_PRECOMMIT_FAULT pid=$ServerProcessId runtime=$runtime observed=PgSleep_before_operations_receipt_commit"
        Write-Output 'NATIVE_PRECOMMIT_HARD_KILL_OBSERVED'
        exit 0
    }
    Start-Sleep -Milliseconds 100
}
throw 'Không quan sát callback commit trong 3 phút; không tính PASS và không kill'
