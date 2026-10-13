param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$PostgresBin,
    [Parameter(Mandatory=$true)][string]$BundleDirectory,
    [Parameter(Mandatory=$true)][string]$RunId
)
. (Join-Path $PSScriptRoot 'matrix-support.ps1')
[string[]]$cases=@('holdbefore','holdafter','settlebefore','settleafter')
[System.Diagnostics.Process]$process=$null
[string]$runtime=''
function Capture-Unrecoverable([string]$database,[string]$label) {
    & (Join-Path $repo 'validation/v08/snapshot-database.ps1') -PostgresBin $PostgresBin -HostName 127.0.0.1 -Port 55439 -DatabaseName $database -UserName cuuvuc_test -OutputFile (Join-Path $output "$label.jsonl") | Out-Null
}
try {
foreach($case in $cases) {
    [string]$database="cv_b01_${RunId}_$case"
    $runtime=Join-Path $repo "run-b01-${RunId}-$case"
    & (Join-Path $repo 'scripts/restore-server.ps1') -BundleDirectory $BundleDirectory -ServerDirectory $runtime -PostgresBin $PostgresBin -HostName 127.0.0.1 -Port 55439 -DatabaseName $database -UserName cuuvuc_test | Add-Content (Join-Path $output 'restore.txt')
    Copy-Item (Join-Path $repo 'target/CuuVucCore-0.8.0-SNAPSHOT.jar') (Join-Path $runtime 'plugins/CuuVucCore-0.8.0-SNAPSHOT.jar')
    Copy-Item (Join-Path $repo 'target/CrashProbe.jar') (Join-Path $runtime 'plugins/CrashProbe.jar')
    [string]$properties=Join-Path $runtime 'server.properties'
    (Get-Content $properties -Raw) -replace '(?m)^server-port=.*$','server-port=25595' -replace '(?m)^server-ip=.*$','server-ip=127.0.0.1' | Set-Content $properties -Encoding utf8
    Sql $database "UPDATE building_contracts SET expires_at=clock_timestamp()+interval '1 day'" | Out-Null
    $process=Launch $runtime "$case-prepare"
    Step $runtime 'clear' 'CLEAR' | Out-Null
    Step $runtime 'baseline-flush' 'BASELINE_FLUSH' | Out-Null
    Step $runtime 'applied' 'JPLACE' | Set-Content (Join-Path $output "$case-original-applied.txt")
    Step $runtime 'world-flush' 'BASELINE_FLUSH' | Out-Null
    Capture-Unrecoverable $database "$case-intact"
    Step $runtime 'fixture-stop' 'STOP' | Out-Null
    if (-not $process.WaitForExit(45000)) {throw "Không stop được fixture case=$case"}
    Sql $database "DELETE FROM operations WHERE request_id=(SELECT event_id FROM contract_block_journal LIMIT 1)" | Set-Content (Join-Path $output "$case-fault-injection.txt")
    $process=Launch $runtime "$case-unrecoverable"
    [string]$classification=Sql $database 'SELECT classification FROM contract_recovery_fences'
    if($classification -ne 'UNRECOVERABLE') {throw "Không phát hiện thiếu proof case=$case classification=$classification"}
    Step $runtime 'incident-auth' 'UNREC_AUTH' | Out-Null
    Step $runtime 'incident-review' 'RECOVERY_REVIEW' | Set-Content (Join-Path $output "$case-review.txt")
    [string]$review=Sql $database 'SELECT review_id FROM recovery_reviews ORDER BY created_at DESC LIMIT 1'
    Set-Content (Join-Path $runtime 'plugins/CrashProbe/review-id.txt') $review -Encoding utf8
    [string]$action=if($case.StartsWith('hold')) {'UNREC_HOLD'} else {'UNREC_SETTLE'}
    Step $runtime 'incident-propose' $action | Set-Content (Join-Path $output "$case-proposal.txt")
    [string]$incident=Sql $database 'SELECT case_id FROM unrecoverable_cases'
    Set-Content (Join-Path $runtime 'plugins/CrashProbe/case-id.txt') $incident -Encoding utf8
    Step $runtime 'incident-self-denied' 'UNREC_SELF_DENIED' | Set-Content (Join-Path $output "$case-self-denied.txt")
    Step $runtime 'incident-revoke' 'UNREC_REVOKE' | Out-Null
    Step $runtime 'incident-revoked-denied' 'UNREC_REVOKED_DENIED' | Set-Content (Join-Path $output "$case-revoked-denied.txt")
    Step $runtime 'incident-regrant' 'UNREC_REGRANT' | Out-Null
    Step $runtime 'incident-show' 'UNREC_SHOW' | Set-Content (Join-Path $output "$case-show.txt")
    Capture-Unrecoverable $database "$case-before-approval"
    if($case.EndsWith('before')) {
        Sql $database 'CREATE FUNCTION b01_case_pause() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(10); RETURN NEW; END $$; CREATE TRIGGER b01_case_pause BEFORE INSERT ON unrecoverable_decisions FOR EACH ROW EXECUTE FUNCTION b01_case_pause();' | Out-Null
        Set-Content (Join-Path $runtime 'plugins/CrashProbe/command.txt') 'incident-approve-crash UNREC_APPROVE' -Encoding utf8
        [DateTime]$deadline=[DateTime]::UtcNow.AddSeconds(15)
        [string]$marker=''
        while([DateTime]::UtcNow -lt $deadline -and $marker -eq '') {
            $marker=Sql $database "SELECT json_build_object('backend_pid',pid,'wait',wait_event,'query',query)::text FROM pg_stat_activity WHERE datname=current_database() AND wait_event='PgSleep' AND query LIKE 'INSERT INTO unrecoverable_decisions%'"
        }
        if($marker -eq '') {throw "Không quan sát decision precommit case=$case"}
        Set-Content (Join-Path $output "$case-precommit-marker.json") $marker -Encoding utf8
        Kill-TestProcess $process $runtime "$case-approval-precommit"
        Sql $database 'DROP TRIGGER b01_case_pause ON unrecoverable_decisions; DROP FUNCTION b01_case_pause();' | Out-Null
    } else {
        Step $runtime 'incident-approved' 'UNREC_APPROVE' | Set-Content (Join-Path $output "$case-approved.txt")
        Kill-TestProcess $process $runtime "$case-approval-committed"
    }
    Capture-Unrecoverable $database "$case-killed"
    $process=Launch $runtime "$case-restart"
    Step $runtime 'incident-world-restart' 'OBSERVE' | Set-Content (Join-Path $output "$case-world.txt")
    Capture-Unrecoverable $database "$case-restarted"
    if($case.EndsWith('before')) {
        if((Get-Content (Join-Path $output "$case-before-approval.jsonl") -Raw) -cne (Get-Content (Join-Path $output "$case-killed.jsonl") -Raw)) {throw "Precommit không rollback nguyên tử case=$case"}
    }
    if((Get-Content (Join-Path $output "$case-killed.jsonl") -Raw) -cne (Get-Content (Join-Path $output "$case-restarted.jsonl") -Raw)) {throw "Startup sửa bằng chứng/tài sản case=$case"}
    Step $runtime 'incident-retry-approval' 'UNREC_APPROVE' | Set-Content (Join-Path $output "$case-retry.txt")
    Capture-Unrecoverable $database "$case-committed"
    Step $runtime 'incident-replay-approval' 'UNREC_APPROVE' | Set-Content (Join-Path $output "$case-replay.txt")
    Capture-Unrecoverable $database "$case-replayed"
    if((Get-Content (Join-Path $output "$case-committed.jsonl") -Raw) -cne (Get-Content (Join-Path $output "$case-replayed.jsonl") -Raw)) {throw "Replay chuyển tiền/sửa hồ sơ case=$case"}
    Step $runtime 'incident-fence-check' 'FENCE_CHECK' | Set-Content (Join-Path $output "$case-fence-denied.txt")
    Step $runtime 'incident-show-final' 'UNREC_SHOW' | Set-Content (Join-Path $output "$case-final-report.txt")
    Copy-Item (Join-Path $runtime 'plugins/CuuVucCore/recovery/cases') (Join-Path $output "$case-reports") -Recurse
    Copy-Item (Get-ChildItem (Join-Path $runtime 'plugins/CuuVucCore/recovery') -Filter '*.tsv' | Sort-Object LastWriteTime | Select-Object -Last 1).FullName (Join-Path $output "$case-recovery.tsv")
    Step $runtime 'incident-final-stop' 'STOP' | Out-Null
    if(-not $process.WaitForExit(45000)) {throw "Không stop được runtime case=$case"}
    Add-Content (Join-Path $output 'completed.txt') "$case UNRECOVERABLE_HARD_KILL_PASS atomic_decision_and_ledgers=true replay_once=true permanent_fence=true missing_block_receipt_not_rebuilt=true"
}
} finally {if($null -ne $process -and -not $process.HasExited) {Kill-TestProcess $process $runtime 'error-cleanup'}}
Get-FileHash (Join-Path $repo 'target/CuuVucCore-0.8.0-SNAPSHOT.jar'),(Join-Path $repo 'target/CrashProbe.jar') -Algorithm SHA256 | Format-List | Out-File (Join-Path $output 'binary-hashes.txt')
& (Join-Path $PSScriptRoot 'verify-unrecoverable-evidence.ps1') -EvidenceDirectory $output | Tee-Object (Join-Path $output 'verify.log')
