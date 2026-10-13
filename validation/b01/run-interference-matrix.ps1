param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$PostgresBin,
    [Parameter(Mandatory=$true)][string]$BundleDirectory,
    [Parameter(Mandatory=$true)][string]$CoreJar,
    [Parameter(Mandatory=$true)][string]$RunId
)
. (Join-Path $PSScriptRoot 'matrix-support.ps1')
if (-not $RunId.StartsWith('if')) {throw 'RunId interference phải bắt đầu bằng if'}
[string[]]$cases=@('beforeplace','beforebreak','afterplace','afterbreak','preplace','prebreak','controlplace','controlbreak')
[System.Diagnostics.Process]$process=$null
[string]$runtime=''
[string]$database=''
function Interference-Step([string]$runtime, [string]$id, [string]$action) {
    [string]$directory=Join-Path $runtime 'plugins/InterferenceProbe'
    Set-Content -LiteralPath (Join-Path $directory 'command.tmp') -Value "$id $action" -Encoding utf8
    Move-Item -LiteralPath (Join-Path $directory 'command.tmp') -Destination (Join-Path $directory 'command.txt')
    Wait-FileText (Join-Path $directory "$id.done") '.+'
    return Get-Content -LiteralPath (Join-Path $directory "$id.done") -Raw
}
function Wait-Sql([string]$database, [string]$query, [string]$expected) {
    [DateTime]$deadline=[DateTime]::UtcNow.AddSeconds(25)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ((Sql $database $query) -eq $expected) {return}
        Start-Sleep -Milliseconds 100
    }
    throw "SQL không đạt expected=$expected database=$database query=$query"
}
function Snapshot-Interference([string]$database, [string]$label) {
    & (Join-Path $repo 'validation/v08/snapshot-database.ps1') -PostgresBin $PostgresBin -HostName 127.0.0.1 -Port 55439 -DatabaseName $database -UserName cuuvuc_test -OutputFile (Join-Path $output "$label.jsonl") | Out-Null
}
try {
foreach ($case in $cases) {
    $database="cv_b01_${RunId}_$case"
    $runtime=Join-Path $repo "run-b01-${RunId}-$case"
    & (Join-Path $repo 'scripts/restore-server.ps1') -BundleDirectory $BundleDirectory -ServerDirectory $runtime -PostgresBin $PostgresBin -HostName 127.0.0.1 -Port 55439 -DatabaseName $database -UserName cuuvuc_test | Add-Content (Join-Path $output 'restore.txt')
    Copy-Item -LiteralPath $CoreJar -Destination (Join-Path $runtime 'plugins/CuuVucCore-0.8.0-SNAPSHOT.jar')
    Copy-Item (Join-Path $repo 'target/CrashProbe.jar') (Join-Path $runtime 'plugins/CrashProbe.jar')
    Copy-Item (Join-Path $repo 'target/InterferenceProbe.jar') (Join-Path $runtime 'plugins/InterferenceProbe.jar')
    [string]$properties=Join-Path $runtime 'server.properties'
    (Get-Content $properties -Raw) -replace '(?m)^server-port=.*$','server-port=25594' -replace '(?m)^server-ip=.*$','server-ip=127.0.0.1' | Set-Content $properties -Encoding utf8
    Sql $database "UPDATE building_contracts SET expires_at=clock_timestamp()+interval '1 day'" | Out-Null
    $process=Launch $runtime "$case-before"
    Add-Content (Join-Path $output 'pids.txt') "$case pid=$($process.Id) runtime=$runtime database=$database"
    [string]$event=if ($case.EndsWith('break')) {'BREAK'} else {'PLACE'}
    Interference-Step $runtime 'baseline' "BASELINE_$event" | Set-Content (Join-Path $output "$case-baseline-world.txt")
    Snapshot-Interference $database "$case-baseline"
    Interference-Step $runtime 'prepare' $event | Set-Content (Join-Path $output "$case-prepare.txt")
    Wait-Sql $database "SELECT count(*) FROM contract_block_journal WHERE state='INTENT'" '1'
    if ($case.StartsWith('before')) {Interference-Step $runtime 'arm' 'ARM_BEFORE' | Out-Null}
    if ($case.StartsWith('after')) {Interference-Step $runtime 'arm' 'ARM_AFTER' | Out-Null}
    if ($case.StartsWith('pre')) {
        & (Join-Path $PostgresBin 'psql.exe') -X --no-password -h 127.0.0.1 -p 55439 -U cuuvuc_test -d $database -v ON_ERROR_STOP=1 -f (Join-Path $PSScriptRoot 'interference/gate.sql') | Set-Content (Join-Path $output "$case-gate-installed.txt")
        if ($LASTEXITCODE -ne 0) {throw "Không cài được gate database=$database"}
        Set-Content (Join-Path $runtime 'plugins/InterferenceProbe/database.txt') $database -Encoding utf8
        Interference-Step $runtime 'watch-precommit' 'WATCH_PRECOMMIT' | Set-Content (Join-Path $output "$case-watcher-armed.txt")
    }
    Interference-Step $runtime 'retry' $event | Set-Content (Join-Path $output "$case-retry.txt")
    if ($case.StartsWith('pre')) {
        [string]$query="SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event='PgSleep' AND query LIKE 'UPDATE operations SET result=%'"
        Wait-FileText (Join-Path $runtime 'plugins/InterferenceProbe/precommit-mutation.done') 'world=STONE'
        Copy-Item (Join-Path $runtime 'plugins/InterferenceProbe/precommit-observed.txt') (Join-Path $output "$case-precommit-marker.txt")
        Copy-Item (Join-Path $runtime 'plugins/InterferenceProbe/precommit-mutation.done') (Join-Path $output "$case-mutation.txt")
        Wait-Sql $database $query '0'
        Sql $database 'DROP TRIGGER b01_interference_gate ON operations; DROP FUNCTION b01_interference_pause();' | Set-Content (Join-Path $output "$case-gate-removed.txt")
    }
    if ($case.StartsWith('before')) {
        Wait-Sql $database "SELECT count(*) FROM contract_block_journal WHERE state<>'INTENT' OR EXISTS(SELECT 1 FROM contract_recovery_fences)" '1'
        if ($case -eq 'beforebreak') {Wait-Sql $database "SELECT count(*) FROM contract_block_journal WHERE clock_timestamp()>prepared_at+interval '12 seconds'" '1'}
    } elseif ($case.StartsWith('after')) {Wait-Sql $database 'SELECT count(*) FROM contract_recovery_fences' '1'}
    elseif ($case.StartsWith('control')) {Wait-Sql $database "SELECT count(*) FROM contract_block_journal WHERE state='APPLIED'" '1'}
    [string]$world=Interference-Step $runtime 'observe-live' 'OBSERVE'
    Set-Content (Join-Path $output "$case-live-world.txt") $world -Encoding utf8
    Snapshot-Interference $database "$case-live"
    Sql $database "SELECT json_build_object('journal',(SELECT json_agg(row_to_json(j)) FROM contract_block_journal j),'fences',(SELECT json_agg(row_to_json(f)) FROM contract_recovery_fences f),'audit',(SELECT count(*) FROM building_history WHERE event_type IN ('PLACE','BREAK')),'receipt',(SELECT count(*) FROM operations WHERE fingerprint LIKE 'contract:block:%'))::text" | Set-Content (Join-Path $output "$case-live-summary.json")
    [string]$fenced=Sql $database "SELECT count(*) FROM contract_recovery_fences WHERE contract_id='279bbdf9-0fe9-4eec-8bb6-46a9df8c72a3'"
    if ($fenced -eq '1') {Step $runtime 'fence-check' 'FENCE_CHECK' | Set-Content (Join-Path $output "$case-denied.txt")}
    [string]$other=Sql $database "SELECT count(*) FROM contract_recovery_fences WHERE contract_id<>'279bbdf9-0fe9-4eec-8bb6-46a9df8c72a3'"
    if ($other -ne '0') {throw 'Interference tạo fence ngoài hợp đồng bị tác động'}
    [object]$assets=(State $database | ConvertFrom-Json)
    if ($assets.money -ne 4 -or $assets.wallet -ne 0 -or $assets.escrow -ne 4 -or $assets.release -ne 0 -or $assets.consume -ne 0 -or $assets.complete_receipt -ne 0) {throw "Tài sản/settlement sai case=$case"}
    Copy-Item (Join-Path $runtime 'plugins/InterferenceProbe/markers.txt') (Join-Path $output "$case-plugin-markers.txt")
    Step $runtime 'world-save' 'BASELINE_FLUSH' | Out-Null
    Kill-TestProcess $process $runtime "$case-restart-fault"
    Snapshot-Interference $database "$case-killed"
    $process=Launch $runtime "$case-restart"
    Interference-Step $runtime 'observe-restart' 'OBSERVE' | Set-Content (Join-Path $output "$case-restart-world.txt")
    Snapshot-Interference $database "$case-restarted"
    Copy-Item (Get-ChildItem (Join-Path $runtime 'plugins/CuuVucCore/recovery') -Filter '*.tsv' | Sort-Object LastWriteTime | Select-Object -Last 1).FullName (Join-Path $output "$case-recovery.tsv")
    Step $runtime 'cleanup-stop' 'STOP' | Out-Null
    if (-not $process.WaitForExit(45000)) {throw "Stop interference hết hạn case=$case"}
    Add-Content (Join-Path $output 'completed.txt') "$case OBSERVED live_fence=$fenced other_contract_unfenced=true assets_unchanged=true $($world.Trim())"
}
} finally {
    if ($null -ne $process -and -not $process.HasExited) {Kill-TestProcess $process $runtime 'error-cleanup'}
}
Get-FileHash -LiteralPath $CoreJar,(Join-Path $repo 'target/CrashProbe.jar'),(Join-Path $repo 'target/InterferenceProbe.jar') -Algorithm SHA256 | Format-List | Out-File (Join-Path $output 'binary-hashes.txt')
& (Join-Path $PSScriptRoot 'verify-interference-evidence.ps1') -EvidenceDirectory $output | Set-Content (Join-Path $output 'reconciliation.txt') -Encoding utf8
