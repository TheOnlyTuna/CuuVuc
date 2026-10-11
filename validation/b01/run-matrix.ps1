param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$PostgresBin,
    [Parameter(Mandatory=$true)][string]$BundleDirectory,
    [Parameter(Mandatory=$true)][string]$RunId
)
. (Join-Path $PSScriptRoot 'matrix-support.ps1')
# Mỗi ca restore backup có manifest vào DB/runtime mới; không ghi checkpoint gốc.
[string[]]$cases = @('pq0','pq1','pc0','pc1','bq0','bc0','bc1','txbefore','txafter')
[System.Diagnostics.Process]$process = $null
try {
foreach ($case in $cases) {
    [string]$database = "cv_b01_${RunId}_$case"
    [string]$runtime = Join-Path $repo "run-b01-${RunId}-$case"
    & (Join-Path $repo 'scripts/restore-server.ps1') -BundleDirectory $BundleDirectory -ServerDirectory $runtime -PostgresBin $PostgresBin -HostName 127.0.0.1 -Port 55439 -DatabaseName $database -UserName cuuvuc_test | Add-Content (Join-Path $output 'restore.txt')
    Copy-Item -LiteralPath (Join-Path $repo 'target/CuuVucCore-0.8.0-SNAPSHOT.jar') -Destination (Join-Path $runtime 'plugins/CuuVucCore-0.8.0-SNAPSHOT.jar')
    Copy-Item -LiteralPath (Join-Path $repo 'target/CrashProbe.jar') -Destination (Join-Path $runtime 'plugins/CrashProbe.jar')
    [string]$properties = Join-Path $runtime 'server.properties'
    (Get-Content -LiteralPath $properties -Raw) -replace '(?m)^server-port=.*$', 'server-port=25590' | Set-Content -LiteralPath $properties -Encoding utf8
    Sql $database "UPDATE building_contracts SET expires_at=clock_timestamp()+interval '1 day'" | Out-Null
    $process = Launch $runtime "$case-before"
    if ($case.StartsWith('p')) { Step $runtime 'baseline-clear' 'CLEAR' | Out-Null; Step $runtime 'baseline-save' 'SAVE' | Out-Null }
    if ($case.StartsWith('tx')) {
        Step $runtime 'save-before-submit' 'SAVE' | Out-Null
        Step $runtime 'submit' 'SUBMIT' | Out-Null
        if ($case -eq 'txbefore') {
            Sql $database 'CREATE FUNCTION b01_pause() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.fingerprint LIKE ''contract:complete:%'' THEN PERFORM pg_sleep(4); END IF; RETURN NEW; END $$; CREATE TRIGGER b01_pause BEFORE UPDATE ON operations FOR EACH ROW EXECUTE FUNCTION b01_pause();' | Out-Null
            [string]$directory = Join-Path $runtime 'plugins/CrashProbe'
            Set-Content -LiteralPath (Join-Path $directory 'command.txt') -Value 'complete COMPLETE' -Encoding utf8
            [DateTime]$deadline = [DateTime]::UtcNow.AddSeconds(4)
            do {
                [string]$waiting = Sql $database "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event='PgSleep' AND query LIKE 'UPDATE operations SET result=%'"
                if ($waiting -eq '1') { break }
                Start-Sleep -Milliseconds 50
            } while ([DateTime]::UtcNow -lt $deadline)
            if ($waiting -ne '1') { throw 'Không xác định được điểm trước commit; không tính PASS' }
        } else { Step $runtime 'complete' 'COMPLETE' | Out-Null }
    } else {
        Step $runtime 'hold' 'HOLD' | Out-Null
        Step $runtime 'edit' $(if ($case.StartsWith('p')) {'PLACE'} else {'BREAK'}) | Out-Null
        if ($case.Contains('c')) {
            Step $runtime 'resume' 'RESUME' | Out-Null
            [DateTime]$deadline = [DateTime]::UtcNow.AddSeconds(5)
            do {
                [string]$count = Sql $database "SELECT count(*) FROM building_history WHERE event_type IN ('PLACE','BREAK')"
                if ($count -eq '1') { break }
                Start-Sleep -Milliseconds 100
            } while ([DateTime]::UtcNow -lt $deadline)
            if ($count -ne '1') { throw 'Audit chưa commit; không tính PASS' }
        }
        if ($case.EndsWith('1')) { Step $runtime 'world-save' 'SAVE' | Out-Null }
    }
    Kill-TestProcess $process $runtime "$case-fault"
    if ($case -eq 'txbefore') { Sql $database 'DROP TRIGGER b01_pause ON operations; DROP FUNCTION b01_pause();' | Out-Null }
    [string]$before = State $database
    Set-Content (Join-Path $output "$case-before.json") $before -Encoding utf8
    $process = Launch $runtime "$case-restart"
    [string]$observed = Step $runtime 'recovered-world' 'OBSERVE'
    Set-Content (Join-Path $output "$case-world.txt") $observed -Encoding utf8
    [string]$after = State $database
    Set-Content (Join-Path $output "$case-after.json") $after -Encoding utf8
    if ($before -cne $after) { throw "Startup đã đổi trạng thái nghiệp vụ ngoài dự kiến: $case" }
    Copy-Item -LiteralPath (Get-ChildItem (Join-Path $runtime 'plugins/CuuVucCore/recovery') -Filter '*.tsv' | Sort-Object LastWriteTime | Select-Object -Last 1).FullName -Destination (Join-Path $output "$case-recovery.tsv")
    if (-not $case.StartsWith('tx')) {
        [string]$expectedWorld = if ($case -in @('pq0','pc0','bc1')) { 'AIR' } else { 'COBBLESTONE' }
        [string]$expectedAssessment = if ($case.Contains('q')) { 'UNKNOWN_HISTORY' } elseif ($case.EndsWith('0')) { 'WORLD_AUDIT_MISMATCH' } else { 'MATCH_OBSERVED_STATE' }
        [string]$assessment = Get-Content (Join-Path $output "$case-recovery.tsv") | Where-Object { $_ -match "`t67:100:5`t" }
        if ($observed.Trim() -ne "action=OBSERVE world=$expectedWorld" -or -not $assessment.EndsWith($expectedAssessment)) { throw "World hoặc báo cáo phục hồi không khớp: $case" }
        [object]$values = $after | ConvertFrom-Json
        [int]$expectedPlace = if ($case.StartsWith('pc')) { 1 } else { 0 }
        [int]$expectedBreak = if ($case.StartsWith('bc')) { 1 } else { 0 }
        if ($values.place -ne $expectedPlace -or $values.break -ne $expectedBreak -or $values.wallet -ne 0 -or $values.escrow -ne 4 -or $values.money -ne 4) { throw "Audit hoặc tài sản không khớp: $case" }
    }
    if ($case.StartsWith('tx')) {
        Step $runtime 'replay1' 'COMPLETE' | Out-Null
        Step $runtime 'replay2' 'COMPLETE' | Out-Null
        [string]$replayed = State $database
        Set-Content (Join-Path $output "$case-replayed.json") $replayed -Encoding utf8
        [object]$values = $replayed | ConvertFrom-Json
        if ($values.status -ne 'COMPLETED' -or $values.wallet -ne 2 -or $values.escrow -ne 2 -or $values.release -ne 1 -or $values.consume -ne 2 -or $values.complete_receipt -ne 1 -or $values.money -ne 4 -or $values.mint -ne 4 -or $values.burn -ne 0) { throw "Replay không bảo toàn tài sản: $case" }
        [string]$resources = Sql $database "SELECT (SELECT coalesce(sum(delta),0) FROM resource_ledger WHERE kind='CONSUME' AND resource_id='linh_thach')=-2 AND (SELECT coalesce(sum(delta),0) FROM resource_ledger WHERE kind='CONSUME' AND resource_id='huyen_thiet')=-1 AND (SELECT sum(quantity) FROM stage_inventory)=1 AND (SELECT count(*) FROM item_tokens)=29 AND (SELECT count(*) FROM contract_escrow_ledger WHERE kind='DEPOSIT')=2 AND NOT EXISTS (SELECT 1 FROM contract_escrow_ledger WHERE kind='REFUND') AND NOT EXISTS (SELECT 1 FROM resource_ledger WHERE kind='TRANSFER' GROUP BY request_id,resource_id HAVING sum(delta)<>0)"
        if ($resources -ne 't') { throw "Nguyên liệu/escrow/token sau replay không khớp: $case" }
    }
    Kill-TestProcess $process $runtime "$case-cleanup"
    Add-Content (Join-Path $output 'completed.txt') "$case HARD_KILL_OBSERVED startup_state_unchanged=true $($observed.Trim())"
}
} finally {
    if ($null -ne $process -and -not $process.HasExited) { Kill-TestProcess $process $runtime 'error-cleanup' }
}
Copy-Item -LiteralPath (Join-Path $BundleDirectory 'manifest.json') -Destination (Join-Path $output 'source-backup-manifest.json')
Get-FileHash (Join-Path $repo 'target/CuuVucCore-0.8.0-SNAPSHOT.jar'),(Join-Path $repo 'target/CrashProbe.jar') -Algorithm SHA256 | Format-List | Out-File (Join-Path $output 'binary-hashes.txt')
