param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$PostgresBin,
    [Parameter(Mandatory=$true)][string]$BundleDirectory,
    [Parameter(Mandatory=$true)][string]$RunId
)
. (Join-Path $PSScriptRoot 'matrix-support.ps1')
[string[]]$cases = @('intent','applied0','applied1','sequence0','sequence1','savecallback','missingproof')
[System.Diagnostics.Process]$process = $null
try {
foreach ($case in $cases) {
    [string]$database = "cv_b01_${RunId}_$case"
    [string]$runtime = Join-Path $repo "run-b01-${RunId}-$case"
    & (Join-Path $repo 'scripts/restore-server.ps1') -BundleDirectory $BundleDirectory -ServerDirectory $runtime -PostgresBin $PostgresBin -HostName 127.0.0.1 -Port 55439 -DatabaseName $database -UserName cuuvuc_test | Add-Content (Join-Path $output 'restore.txt')
    Copy-Item (Join-Path $repo 'target/CuuVucCore-0.8.0-SNAPSHOT.jar') (Join-Path $runtime 'plugins/CuuVucCore-0.8.0-SNAPSHOT.jar')
    Copy-Item (Join-Path $repo 'target/CrashProbe.jar') (Join-Path $runtime 'plugins/CrashProbe.jar')
    [string]$properties = Join-Path $runtime 'server.properties'
    (Get-Content $properties -Raw) -replace '(?m)^server-port=.*$', 'server-port=25592' -replace '(?m)^server-ip=.*$', 'server-ip=127.0.0.1' | Set-Content $properties -Encoding utf8
    Sql $database "UPDATE building_contracts SET expires_at=clock_timestamp()+interval '1 day'" | Out-Null
    $process = Launch $runtime "$case-before"
    Step $runtime 'clear' 'CLEAR' | Out-Null
    Step $runtime 'baseline-save' 'SAVE' | Out-Null
    if ($case -eq 'intent') { Step $runtime 'intent' 'INTENT' | Out-Null }
    else { Step $runtime 'place' 'JPLACE' | Out-Null }
    if ($case.StartsWith('sequence')) {
        Step $runtime 'break' 'JBREAK' | Out-Null
        Step $runtime 'place-again' 'JPLACE' | Out-Null
    }
    if ($case.EndsWith('1') -or $case -eq 'missingproof') { Step $runtime 'save' 'SAVE' | Out-Null }
    if ($case -eq 'savecallback') {
        Step $runtime 'arm-save' 'ARM_SAVE_CRASH' | Out-Null
        Set-Content (Join-Path $runtime 'plugins/CrashProbe/command.txt') 'crash-save SAVE' -Encoding utf8
        Wait-FileText (Join-Path $runtime 'plugins/CrashProbe/native-save-entered.txt') 'WORLD_SAVE_EVENT_ENTERED'
        Copy-Item (Join-Path $runtime 'plugins/CrashProbe/native-save-entered.txt') (Join-Path $output 'native-save-boundary.txt')
    }
    Kill-TestProcess $process $runtime "$case-fault"
    if ($case -eq 'missingproof') {
        Sql $database "DELETE FROM operations WHERE request_id=(SELECT event_id FROM contract_block_journal LIMIT 1)" | Out-Null
    }
    [string]$before = State $database
    Set-Content (Join-Path $output "$case-before.json") $before -Encoding utf8
    $process = Launch $runtime "$case-restart"
    [string]$observed = Step $runtime 'world' 'OBSERVE'
    [string]$after = State $database
    Set-Content (Join-Path $output "$case-after.json") $after -Encoding utf8
    Set-Content (Join-Path $output "$case-world.txt") $observed -Encoding utf8
    if ($before -cne $after) { throw "Startup đã sửa lịch sử hoặc tài sản: $case" }
    [bool]$fenced = $case -notin @('applied1','sequence1')
    [string]$classification = if ($case -eq 'intent') { 'INCOMPLETE' } elseif ($case -eq 'missingproof') { 'UNRECOVERABLE' } elseif ($fenced) { 'MISMATCH' } else { 'CONFIRMED' }
    [string]$fence = Sql $database "SELECT coalesce((SELECT classification FROM contract_recovery_fences WHERE contract_id='279bbdf9-0fe9-4eec-8bb6-46a9df8c72a3'),'CONFIRMED')"
    if ($fence -ne $classification) { throw "Phân loại không khớp case=$case expected=$classification actual=$fence" }
    [string]$other = Sql $database "SELECT count(*) FROM contract_recovery_fences WHERE contract_id<>'279bbdf9-0fe9-4eec-8bb6-46a9df8c72a3'"
    if ($other -ne '0') { throw 'Không được khóa hợp đồng console UNKNOWN_HISTORY không liên quan' }
    if ($fenced) { Step $runtime 'fence-check' 'FENCE_CHECK' | Set-Content (Join-Path $output "$case-denied.txt") }
    [int]$expectedJournal = if ($case.StartsWith('sequence')) { 3 } else { 1 }
    [int]$expectedAudit = if ($case -eq 'intent') { 0 } else { $expectedJournal }
    [object]$values = $after | ConvertFrom-Json
    if ($values.place + $values.break -ne $expectedAudit -or $values.money -ne 4 -or $values.escrow -ne 4 -or $values.release -ne 0 -or $values.consume -ne 0 -or $values.wallet -ne 0) { throw "Tài sản/audit sai: $case" }
    [string]$journalCount = Sql $database 'SELECT count(*) FROM contract_block_journal'
    if ($journalCount -ne "$expectedJournal") { throw "Mất sự kiện trung gian: $case" }
    Sql $database "SELECT event_id,contract_id,event_type,position,state,audit_event_id FROM contract_block_journal ORDER BY prepared_at,event_id" | Set-Content (Join-Path $output "$case-journal.txt")
    Sql $database "SELECT contract_id,classification,reason FROM contract_recovery_fences" | Set-Content (Join-Path $output "$case-fence.txt")
    Copy-Item (Get-ChildItem (Join-Path $runtime 'plugins/CuuVucCore/recovery') -Filter '*.tsv' | Sort-Object LastWriteTime | Select-Object -Last 1).FullName (Join-Path $output "$case-recovery.tsv")
    Kill-TestProcess $process $runtime "$case-cleanup"
    Add-Content (Join-Path $output 'completed.txt') "$case HARD_KILL_PASS classification=$classification journal=$expectedJournal audit=$expectedAudit other_contract_unfenced=true $($observed.Trim())"
}
} finally {
    if ($null -ne $process -and -not $process.HasExited) { Kill-TestProcess $process $runtime 'error-cleanup' }
}
Get-FileHash (Join-Path $repo 'target/CuuVucCore-0.8.0-SNAPSHOT.jar'),(Join-Path $repo 'target/CrashProbe.jar') -Algorithm SHA256 | Format-List | Out-File (Join-Path $output 'binary-hashes.txt')
