Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($RunId -notmatch '^[a-z][a-z0-9]{0,12}$') { throw 'RunId phải là định danh ngắn, chưa dùng.' }
[string]$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
[string]$output = Join-Path $PSScriptRoot $RunId
if (Test-Path -LiteralPath $output) { throw "Đích bằng chứng đã tồn tại: $output" }
New-Item -ItemType Directory -Path $output | Out-Null
$env:CUUVUC_DB_PASSWORD = 'local-trust-test'

function Sql([string]$database, [string]$query) {
    [string[]]$rows = @(& (Join-Path $PostgresBin 'psql.exe') -X -h localhost -p 55439 -U cuuvuc_test -d $database -At -v ON_ERROR_STOP=1 -c $query)
    if ($LASTEXITCODE -ne 0) { throw "SQL thất bại database=$database query=$query" }
    return ($rows -join "`n").Trim()
}

function Wait-FileText([string]$path, [string]$pattern) {
    [DateTime]$deadline = [DateTime]::UtcNow.AddSeconds(45)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ((Test-Path -LiteralPath $path) -and (Get-Content -LiteralPath $path -Raw) -match $pattern) { return }
        Start-Sleep -Milliseconds 100
    }
    throw "Hết hạn chờ path=$path pattern=$pattern"
}

function Launch([string]$runtime, [string]$label) {
    [string]$jar = Join-Path $runtime 'paper.jar'
    [System.Diagnostics.Process]$process = Start-Process -FilePath (Join-Path $JavaHome 'bin/java.exe') -ArgumentList @('-Dterminal.jline=false','-Dterminal.ansi=false','-Xms256M','-Xmx768M','-jar',('"'+$jar+'"'),'--nogui') -WorkingDirectory $runtime -WindowStyle Hidden -RedirectStandardOutput (Join-Path $output "$label.stdout.log") -RedirectStandardError (Join-Path $output "$label.stderr.log") -PassThru
    [bool]$ready = $false
    try {
        Wait-FileText (Join-Path $output "$label.stdout.log") 'cuuvuc_database_ready'
        $ready = $true
        return $process
    } finally {
        if (-not $ready -and -not $process.HasExited) { Kill-TestProcess $process $runtime "$label-startup-failed" }
    }
}

function Kill-TestProcess([System.Diagnostics.Process]$process, [string]$runtime, [string]$label) {
    [string]$resolved = (Resolve-Path -LiteralPath $runtime).Path
    if (-not $resolved.StartsWith($repo + '\run-b01-', [StringComparison]::OrdinalIgnoreCase)) { throw "Từ chối kill runtime ngoài phạm vi: $resolved" }
    [object]$actual = Get-CimInstance Win32_Process -Filter "ProcessId = $($process.Id)"
    if ($null -eq $actual -or -not $actual.CommandLine.Contains((Join-Path $resolved 'paper.jar'))) { throw "PID không khớp runtime riêng: $($process.Id)" }
    Stop-Process -Id $process.Id -Force
    $process.WaitForExit()
    Add-Content (Join-Path $output 'kills.txt') "$label pid=$($process.Id) exit=$($process.ExitCode) runtime=$resolved"
}

function Step([string]$runtime, [string]$id, [string]$action) {
    [string]$directory = Join-Path $runtime 'plugins/CrashProbe'
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $directory 'command.tmp') -Value "$id $action" -Encoding utf8
    Move-Item -LiteralPath (Join-Path $directory 'command.tmp') -Destination (Join-Path $directory 'command.txt')
    Wait-FileText (Join-Path $directory "$id.done") '.+'
    return Get-Content -LiteralPath (Join-Path $directory "$id.done") -Raw
}

function State([string]$database) {
    return Sql $database "SELECT json_build_object('status',(SELECT status FROM building_contracts WHERE template_id='STONE_PAD_V1'),'place',(SELECT count(*) FROM building_history WHERE event_type='PLACE'),'break',(SELECT count(*) FROM building_history WHERE event_type='BREAK'),'wallet',(SELECT balance FROM profiles WHERE player_name='TyninWorld'),'escrow',(SELECT sum(balance) FROM contract_escrow_accounts),'release',(SELECT count(*) FROM contract_escrow_ledger WHERE kind='RELEASE'),'consume',(SELECT count(*) FROM resource_ledger WHERE kind='CONSUME'),'complete_receipt',(SELECT count(*) FROM operations WHERE fingerprint LIKE 'contract:complete:%'),'money',(SELECT sum(balance) FROM profiles)+(SELECT sum(balance) FROM sect_accounts)+(SELECT sum(balance) FROM contract_escrow_accounts),'mint',(SELECT sum(minted) FROM economy_daily),'burn',(SELECT sum(burned) FROM economy_daily))::text"
}
