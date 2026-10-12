param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$PostgresBin,
    [Parameter(Mandatory=$true)][string]$BundleDirectory,
    [Parameter(Mandatory=$true)][string]$RunId
)
. (Join-Path $PSScriptRoot 'matrix-support.ps1')
[string]$owner = 'f3d83b55-4452-39bc-85f4-29f8cebdcc4f'
[System.Diagnostics.Process]$process = $null
function Await-Authority([string]$database, [string]$enabled) {
    [DateTime]$deadline = [DateTime]::UtcNow.AddSeconds(15)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ((Sql $database "SELECT enabled FROM recovery_authorities WHERE player_id='$owner'") -eq $enabled) { return }
        Start-Sleep -Milliseconds 100
    }
    throw "Quyền DB không đạt expected=$enabled database=$database"
}
function Capture-Review([string]$runtime, [string]$database, [string]$label) {
    Step $runtime $label 'RECOVERY_REVIEW' | Set-Content (Join-Path $output "$label.txt")
    [string]$review = Sql $database 'SELECT review_id FROM recovery_reviews ORDER BY created_at DESC LIMIT 1'
    Set-Content (Join-Path $runtime 'plugins/CrashProbe/review-id.txt') $review -Encoding utf8
    Sql $database "SELECT row_to_json(r) FROM recovery_reviews r WHERE review_id='$review'" | Set-Content (Join-Path $output "$label.json")
}
try {
foreach ($case in @('before','after')) {
    [string]$database = "cv_b01_${RunId}_$case"
    [string]$runtime = Join-Path $repo "run-b01-${RunId}-$case"
    & (Join-Path $repo 'scripts/restore-server.ps1') -BundleDirectory $BundleDirectory -ServerDirectory $runtime -PostgresBin $PostgresBin -HostName 127.0.0.1 -Port 55439 -DatabaseName $database -UserName cuuvuc_test | Add-Content (Join-Path $output 'restore.txt')
    Copy-Item (Join-Path $repo 'target/CuuVucCore-0.8.0-SNAPSHOT.jar') (Join-Path $runtime 'plugins/CuuVucCore-0.8.0-SNAPSHOT.jar')
    Copy-Item (Join-Path $repo 'target/CrashProbe.jar') (Join-Path $runtime 'plugins/CrashProbe.jar')
    [string]$properties = Join-Path $runtime 'server.properties'
    (Get-Content $properties -Raw) -replace '(?m)^server-port=.*$', 'server-port=25593' -replace '(?m)^server-ip=.*$', 'server-ip=127.0.0.1' | Set-Content $properties -Encoding utf8
    Sql $database "UPDATE building_contracts SET expires_at=clock_timestamp()+interval '1 day'" | Out-Null
    $process = Launch $runtime "$case-prepare"
    Step $runtime 'clear' 'CLEAR' | Out-Null
    Step $runtime 'save' 'SAVE' | Out-Null
    Step $runtime 'intent' 'INTENT' | Out-Null
    Step $runtime 'stop-preparation' 'STOP' | Out-Null
    if (-not $process.WaitForExit(30000)) { throw 'Không shutdown được runtime chuẩn bị' }
    $process = Launch $runtime "$case-fenced"
    Step $runtime 'grant' 'RECOVERY_GRANT' | Out-Null
    Await-Authority $database 't'
    Capture-Review $runtime $database "$case-first-review"
    if ($case -eq 'after') {
        Step $runtime 'revoke' 'RECOVERY_REVOKE' | Out-Null
        Await-Authority $database 'f'
        Step $runtime 'revoked-reject' 'RECOVERY_REJECT' | Set-Content (Join-Path $output 'revoked-rejected.txt')
        Step $runtime 'regrant' 'RECOVERY_GRANT' | Out-Null
        Await-Authority $database 't'
        Step $runtime 'stale-reject' 'RECOVERY_REJECT' | Set-Content (Join-Path $output 'stale-rejected.txt')
        Capture-Review $runtime $database "$case-fresh-review"
    }
    [string]$businessBefore = State $database
    Set-Content (Join-Path $output "$case-business-before.json") $businessBefore
    if ($case -eq 'before') {
        Sql $database 'CREATE FUNCTION resolution_pause() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(10); RETURN OLD; END $$; CREATE TRIGGER resolution_pause BEFORE DELETE ON contract_recovery_fences FOR EACH ROW EXECUTE FUNCTION resolution_pause();' | Out-Null
        Set-Content (Join-Path $runtime 'plugins/CrashProbe/command.txt') 'resolve-crash RECOVERY_RESOLVE' -Encoding utf8
        [DateTime]$deadline = [DateTime]::UtcNow.AddSeconds(15)
        [string]$marker = ''
        while ([DateTime]::UtcNow -lt $deadline -and $marker -eq '') {
            $marker = Sql $database "SELECT json_build_object('pid',pid,'query',query,'wait_event',wait_event) FROM pg_stat_activity WHERE datname=current_database() AND wait_event='PgSleep' AND query LIKE 'DELETE FROM contract_recovery_fences%'"
        }
        if ($marker -eq '') { throw 'Không thấy transaction quyết định đang chờ trước commit' }
        Set-Content (Join-Path $output 'precommit-marker.json') $marker
        Kill-TestProcess $process $runtime 'resolution-before-commit'
        Sql $database 'DROP TRIGGER resolution_pause ON contract_recovery_fences; DROP FUNCTION resolution_pause();' | Out-Null
    } else {
        Step $runtime 'resolve-committed' 'RECOVERY_RESOLVE' | Set-Content (Join-Path $output 'committed-result.txt')
        Kill-TestProcess $process $runtime 'resolution-after-commit'
    }
    $process = Launch $runtime "$case-restart"
    [string]$state = Sql $database "SELECT json_build_object('intent',(SELECT count(*) FROM contract_block_journal WHERE state='INTENT'),'reviewed',(SELECT count(*) FROM contract_block_journal WHERE state='REVIEWED_UNCONFIRMED'),'decision',(SELECT count(*) FROM recovery_decisions),'fence',(SELECT count(*) FROM contract_recovery_fences),'audit',(SELECT count(*) FROM building_history WHERE event_type IN ('PLACE','BREAK')),'tokens',(SELECT count(*) FROM item_tokens),'reserve',(SELECT sum(quantity) FROM stage_inventory))"
    Set-Content (Join-Path $output "$case-restarted-state.json") $state
    [object]$values = $state | ConvertFrom-Json
    if ($values.audit -ne 0 -or $values.tokens -ne 29 -or $values.reserve -ne 4 -or $businessBefore -cne (State $database)) { throw "Tài sản hoặc lịch sử đã thay đổi: $case" }
    if ($case -eq 'before') {
        if ($values.intent -ne 1 -or $values.reviewed -ne 0 -or $values.decision -ne 0 -or $values.fence -ne 1) { throw 'Quyết định trước commit không rollback nguyên tử' }
        Step $runtime 'fence-still-blocks' 'FENCE_CHECK' | Set-Content (Join-Path $output 'precommit-fence-denied.txt')
        Step $runtime 'retry-resolution' 'RECOVERY_RESOLVE' | Set-Content (Join-Path $output 'retry-result.txt')
    } else {
        if ($values.intent -ne 0 -or $values.reviewed -ne 1 -or $values.decision -ne 1 -or $values.fence -ne 0) { throw 'Quyết định đã commit không bền sau kill' }
    }
    Step $runtime 'repeat-resolution' 'RECOVERY_RESOLVE' | Set-Content (Join-Path $output "$case-replay-result.txt")
    if ((Sql $database 'SELECT count(*) FROM recovery_decisions') -ne '1') { throw 'Quyết định bị nhân đôi khi replay' }
    Step $runtime 'world-after' 'OBSERVE' | Set-Content (Join-Path $output "$case-world.txt")
    Sql $database 'SELECT row_to_json(d) FROM recovery_decisions d; SELECT event_id,state,audit_event_id FROM contract_block_journal; SELECT row_to_json(a) FROM recovery_authority_history a ORDER BY entry_id' | Set-Content (Join-Path $output "$case-final-proof.txt")
    Copy-Item (Get-ChildItem (Join-Path $runtime 'plugins/CuuVucCore/recovery') -Filter '*.tsv' | Sort-Object LastWriteTime | Select-Object -Last 1).FullName (Join-Path $output "$case-recovery.tsv")
    Step $runtime 'stop-final' 'STOP' | Out-Null
    if (-not $process.WaitForExit(30000)) { throw 'Không shutdown được runtime cuối' }
    Add-Content (Join-Path $output 'completed.txt') "$case RESOLUTION_HARD_KILL_PASS atomic_decision=true replay_once=true no_asset_movement=true"
}
} finally {
    if ($null -ne $process -and -not $process.HasExited) { Kill-TestProcess $process $runtime 'error-cleanup' }
}
Get-FileHash (Join-Path $repo 'target/CuuVucCore-0.8.0-SNAPSHOT.jar'),(Join-Path $repo 'target/CrashProbe.jar') -Algorithm SHA256 | Format-List | Out-File (Join-Path $output 'binary-hashes.txt')
