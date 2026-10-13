param([Parameter(Mandatory=$true)][string]$EvidenceDirectory)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
[string]$directory=(Resolve-Path -LiteralPath $EvidenceDirectory).Path
[string[]]$cases=@('beforeplace','beforebreak','afterplace','afterbreak','preplace','prebreak','controlplace','controlbreak')
[string[]]$journalTables=@('contract_block_journal','contract_recovery_fences','contract_state','operations','building_history')
foreach ($case in $cases) {
    [object]$summary=Get-Content (Join-Path $directory "$case-live-summary.json") -Raw | ConvertFrom-Json
    [int]$proofs=if ($case.StartsWith('control')) {1} else {0}
    [int]$fences=if ($case -eq 'beforeplace' -or $case.StartsWith('control')) {0} else {1}
    [string]$state=if ($case.StartsWith('control')) {'APPLIED'} elseif ($case -eq 'beforeplace') {'ABORTED'} else {'INTENT'}
    if (@($summary.journal).Count -ne 1 -or $summary.journal[0].state -ne $state -or $summary.audit -ne $proofs -or $summary.receipt -ne $proofs) {
        throw "Journal/audit/receipt sai case=$case expected_state=$state expected_proofs=$proofs summary=$($summary | ConvertTo-Json -Depth 8 -Compress)"
    }
    [object[]]$actualFences=@()
    if ($null -ne $summary.fences) {$actualFences=@($summary.fences)}
    if ($actualFences.Count -ne $fences) {throw "World bị can thiệp nhưng fence sai case=$case expected=$fences actual=$($actualFences.Count)"}
    if ($fences -eq 1 -and ($actualFences[0].contract_id -ne '279bbdf9-0fe9-4eec-8bb6-46a9df8c72a3' -or $actualFences[0].classification -ne 'INCOMPLETE')) {throw "Fence sai phạm vi/phân loại case=$case"}
    [string]$world=Get-Content (Join-Path $directory "$case-live-world.txt") -Raw
    [string]$expectedWorld=if ($case -eq 'controlplace') {'COBBLESTONE'} elseif ($case -in @('beforeplace','controlbreak')) {'AIR'} else {'STONE'}
    if ($world.Trim() -ne "world=$expectedWorld") {throw "World sai case=$case actual=$world"}
    [object[]]$baseline=@(Get-Content (Join-Path $directory "$case-baseline.jsonl") | ForEach-Object {ConvertFrom-Json $_})
    [object[]]$live=@(Get-Content (Join-Path $directory "$case-live.jsonl") | ForEach-Object {ConvertFrom-Json $_})
    if ($baseline.Count -ne $live.Count) {throw "Số bảng sai case=$case"}
    foreach ($table in $baseline) {
        [object[]]$actual=@($live | Where-Object table -eq $table.table)
        if ($actual.Count -ne 1) {throw "Bảng bị thiếu/trùng case=$case table=$($table.table)"}
        if ($table.table -notin $journalTables -and ($table.rows | ConvertTo-Json -Depth 30 -Compress) -cne ($actual[0].rows | ConvertTo-Json -Depth 30 -Compress)) {throw "Tài sản/ledger/business đổi case=$case table=$($table.table)"}
    }
    foreach ($tableName in @('operations','building_history')) {
        [int]$beforeCount=@(($baseline | Where-Object table -eq $tableName).rows).Count
        [int]$afterCount=@(($live | Where-Object table -eq $tableName).rows).Count
        if ($afterCount -ne $beforeCount+$proofs) {throw "Có receipt/audit ngoài sự kiện cho phép case=$case table=$tableName"}
    }
    if ((Get-Content (Join-Path $directory "$case-live.jsonl") -Raw) -cne (Get-Content (Join-Path $directory "$case-killed.jsonl") -Raw)) {throw "DB đổi khi save/kill case=$case"}
    if ((Get-Content (Join-Path $directory "$case-killed.jsonl") -Raw) -cne (Get-Content (Join-Path $directory "$case-restarted.jsonl") -Raw)) {throw "Startup đổi bằng chứng hoặc tài sản case=$case"}
    if ($case.StartsWith('pre')) {
        [string]$marker=Get-Content (Join-Path $directory "$case-precommit-marker.txt") -Raw
        if ($marker -notmatch 'backend_pid=\d+ query=UPDATE operations SET result=') {throw "Không có marker transaction thật case=$case"}
        [string]$log=Get-Content (Join-Path $directory "$case-before.stdout.log") -Raw
        if ($log -match 'canceling statement due to statement timeout') {throw "Gate chạm timeout SQL; không tính precommit PASS case=$case"}
    }
    if ($proofs -eq 1) {
        [object]$entry=$summary.journal[0]
        [object[]]$receipts=@(($live | Where-Object table -eq 'operations').rows | Where-Object request_id -eq $entry.event_id)
        [object[]]$audit=@(($live | Where-Object table -eq 'building_history').rows | Where-Object event_id -eq $entry.audit_event_id)
        if ($receipts.Count -ne 1 -or $audit.Count -ne 1 -or $receipts[0].result -eq 'pending' -or
            $receipts[0].fingerprint -ne ('contract:block:'+ $entry.contract_id+':'+$entry.actor_id+':'+$entry.event_type+':'+$entry.position) -or
            $audit[0].event_type -ne $entry.event_type -or $audit[0].detail -ne $entry.position -or
            $audit[0].actor_id -ne $entry.actor_id -or $audit[0].contract_id -ne $entry.contract_id) {throw "Proof audit/receipt lệch case=$case"}
    }
    Write-Output "$case INTERFERENCE_PASS state=$state proofs=$proofs fences=$fences world=$expectedWorld tables=$($live.Count) assets_unchanged=true startup_unchanged=true"
}
