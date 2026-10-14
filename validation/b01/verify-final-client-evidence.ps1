param([Parameter(Mandatory=$true)][string]$EvidenceDirectory)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
[string]$directory=(Resolve-Path -LiteralPath $EvidenceDirectory).Path
function Read-Snapshot([string]$label) {
    [hashtable]$tables=@{}
    Get-Content (Join-Path $directory "$label.jsonl") | ForEach-Object {
        [object]$table=ConvertFrom-Json $_
        if($tables.ContainsKey($table.table)){throw "Bảng trùng label=$label table=$($table.table)"}
        $tables[$table.table]=@($table.rows)
    }
    if($tables.Count -ne 39){throw "Snapshot thiếu bảng label=$label count=$($tables.Count)"}
    return $tables
}
function Canonical([object]$value){return ConvertTo-Json -InputObject $value -Depth 70 -Compress}
function Same-Tables([hashtable]$before,[hashtable]$after,[string[]]$allowed,[string]$label){
    foreach($table in $before.Keys){
        if($table -notin $allowed -and (Canonical $before[$table]) -cne (Canonical $after[$table])){throw "Bảng đổi ngoài phạm vi label=$label table=$table"}
    }
}
function Same-Files([string]$before,[string]$after){
    if((Get-Content (Join-Path $directory "$before.jsonl") -Raw) -cne (Get-Content (Join-Path $directory "$after.jsonl") -Raw)){throw "Snapshot không giống label=$before/$after"}
    Write-Output "SNAPSHOT_PASS before=$before after=$after tables=39"
}
function Resource-Totals([hashtable]$tables){
    return Canonical @(@($tables.inventory+$tables.project_inventory+$tables.stage_inventory) | Group-Object resource_id | Sort-Object Name | ForEach-Object {
        [pscustomobject]@{resource=$_.Name;quantity=[long](($_.Group.quantity | Measure-Object -Sum).Sum)}
    })
}
[hashtable]$baseline=Read-Snapshot 'active-baseline'
[hashtable]$native=Read-Snapshot 'native-intact'
[object[]]$applied=@($native.contract_block_journal | Where-Object state -eq 'APPLIED')
if($applied.Count -ne 2 -or @($applied | Where-Object event_type -eq 'PLACE').Count -ne 1 -or @($applied | Where-Object event_type -eq 'BREAK').Count -ne 1 -or
    @($native.contract_block_journal | Where-Object state -eq 'ABORTED').Count -ne 3){throw 'Số callback Client/intent hết hạn không khớp'}
foreach($entry in $applied){
    [object[]]$receipts=@($native.operations | Where-Object request_id -eq $entry.event_id)
    [object[]]$audit=@($native.building_history | Where-Object event_id -eq $entry.audit_event_id)
    if($entry.actor_id -ne '3bb5ddbc-5b75-377f-8647-6f25c51f252a' -or $entry.position -ne '67:100:5' -or
        $receipts.Count -ne 1 -or $receipts[0].result -eq 'pending' -or $receipts[0].actor_id -ne $entry.actor_id -or
        $receipts[0].fingerprint -ne ('contract:block:'+$entry.contract_id+':'+$entry.actor_id+':'+$entry.event_type+':'+$entry.position) -or
        $audit.Count -ne 1 -or $audit[0].event_type -ne $entry.event_type -or $audit[0].actor_id -ne $entry.actor_id -or $audit[0].detail -ne $entry.position){throw "Proof Client APPLIED sai event=$($entry.event_id)"}
}
Same-Tables $baseline $native @('contract_block_journal','operations','building_history','contract_state') 'native-client'
Same-Tables (Read-Snapshot 'client-place-break') (Read-Snapshot 'outside-denied') @('contract_block_journal') 'outside-denied-with-expired-intent'
Same-Files 'fenced-before-client' 'fenced-after-builder'
Same-Files 'hold-before-approval' 'hold-after-revoked-denial'
Same-Tables (Read-Snapshot 'hold-ready') (Read-Snapshot 'hold-approved') @('operations','unrecoverable_decisions') 'hold-approval'
Same-Files 'hold-approved' 'hold-replayed'
Same-Files 'settle-approved' 'settle-replayed'
Same-Files 'final-before-restart' 'final-stopped'
Same-Files 'final-stopped' 'final-restarted'
Same-Files 'final-restarted' 'post-restart-client-denied'
Same-Files 'final-restarted' 'final-after-stop'
[hashtable]$before=Read-Snapshot 'settle-before-approval'
[hashtable]$after=Read-Snapshot 'final-restarted'
Same-Tables $native $after @('building_contracts','building_history','contract_escrow_accounts','contract_escrow_ledger','contract_recovery_fences','contract_state','ledger','operations','profiles','project_history','project_inventory','project_stages','projects','recovery_authorities','recovery_authority_history','recovery_reviews','resource_ledger','sect_accounts','sect_history','sects','stage_inventory','unrecoverable_cases','unrecoverable_decisions') 'final-allowed-tables'
if((Canonical $native.contract_block_journal) -cne (Canonical $after.contract_block_journal) -or (Canonical $before.unrecoverable_cases) -cne (Canonical $after.unrecoverable_cases)){throw 'Journal/hồ sơ gốc bị sửa'}
foreach($case in $after.unrecoverable_cases){
    [object[]]$decisions=@($after.unrecoverable_decisions | Where-Object case_id -eq $case.case_id)
    if($case.proposer_id -ne 'f3d83b55-4452-39bc-85f4-29f8cebdcc4f' -or $decisions.Count -ne 1 -or $decisions[0].outcome -ne 'APPROVED' -or
        $decisions[0].actor_id -ne '1194cfbc-1195-338c-9eb1-0c49dd118ae3' -or $decisions[0].authority_revision -ne 3){throw "Quyết định độc lập sai case=$($case.case_id)"}
    [object[]]$receipts=@($after.operations | Where-Object request_id -eq $decisions[0].request_id)
    if($receipts.Count -ne 1 -or $receipts[0].actor_id -ne $decisions[0].actor_id -or $receipts[0].result -eq 'pending' -or
        $receipts[0].fingerprint -ne ('recovery:unrecoverable:approve:'+$case.case_id)){throw 'Receipt hành chính sai'}
}
if($after.unrecoverable_cases.Count -ne 2 -or $after.unrecoverable_decisions.Count -ne 2 -or
    @($after.recovery_authorities | Where-Object enabled -eq $true).Count -ne 0 -or
    @(Get-Content (Join-Path $directory 'ops-revoked.json') -Raw | ConvertFrom-Json).Count -ne 0){throw 'Hồ sơ/quyền cuối sai'}
[object]$place=@($applied | Where-Object event_type -eq 'PLACE')[0]
if(@($after.operations | Where-Object request_id -eq $place.event_id).Count -ne 0 -or $after.operations.Count -ne $native.operations.Count+1){throw 'Receipt thiếu bị tạo lại hoặc receipt thừa'}
if((Canonical @($native.operations | Where-Object request_id -ne $place.event_id)) -cne (Canonical @($after.operations | Where-Object fingerprint -notlike 'recovery:unrecoverable:approve:*'))){throw 'Receipt không liên quan bị sửa'}
[object[]]$auditBefore=@($native.building_history | Where-Object event_type -in @('PLACE','BREAK','COMPLETE'))
[object[]]$auditAfter=@($after.building_history | Where-Object event_type -in @('PLACE','BREAK','COMPLETE'))
if((Canonical $auditBefore) -cne (Canonical $auditAfter)){throw 'Audit thi công bị tạo/sửa'}
if($after.building_history.Count -ne $native.building_history.Count+1 -or @($after.building_history | Where-Object event_type -eq 'CANCEL').Count -ne 1){throw 'Audit hành chính sai số lượng'}
[long]$money=[long](($after.profiles.balance+$after.sect_accounts.balance+$after.contract_escrow_accounts.balance | Measure-Object -Sum).Sum)
if($money -ne 4 -or (Resource-Totals $baseline) -cne (Resource-Totals $after) -or (Canonical $baseline.inventory) -cne (Canonical $after.inventory) -or
    (Canonical $baseline.item_tokens) -cne (Canonical $after.item_tokens)){throw 'Tài sản không được bảo toàn'}
[object[]]$settlement=@($after.contract_escrow_ledger | Where-Object kind -eq 'RECOVERY_SETTLEMENT')
if($settlement.Count -ne 1 -or $settlement[0].delta -ne -2 -or @($after.contract_escrow_ledger | Where-Object kind -in @('RELEASE','REFUND')).Count -ne 0){throw 'Settlement escrow sai'}
[object[]]$wallet=@($after.ledger | Where-Object request_id -eq $settlement[0].request_id)
if($wallet.Count -ne 2 -or @($wallet | Where-Object { $_.delta -eq 1 -and $_.reason -like 'RECOVERY_RETURN:*' -and $_.sect_id -eq '5e305313-c14d-473f-8101-e76fb79db1ee' }).Count -ne 1 -or
    @($wallet | Where-Object { $_.delta -eq 1 -and $_.reason -like 'RECOVERY_COMPENSATION_NOT_WORK_PAYMENT:*' -and $_.player_id -eq '3bb5ddbc-5b75-377f-8647-6f25c51f252a' }).Count -ne 1){throw 'Hoàn/bồi thường sai người hoặc mức'}
[object[]]$transfers=@($after.resource_ledger | Where-Object request_id -eq $settlement[0].request_id)
if($transfers.Count -ne 4 -or @($after.resource_ledger | Where-Object kind -eq 'CONSUME').Count -ne 0){throw 'Hoàn reserve không đúng'}
foreach($group in @($transfers | Group-Object resource_id)){if(($group.Group.delta | Measure-Object -Sum).Sum -ne 0){throw 'TRANSFER không cân bằng'}}
if($after.contract_recovery_fences.Count -ne 1 -or $after.contract_recovery_fences[0].classification -ne 'UNRECOVERABLE' -or
    $after.contract_recovery_fences[0].contract_id -ne '279bbdf9-0fe9-4eec-8bb6-46a9df8c72a3'){throw 'Fence sai phạm vi'}
if(@($after.building_contracts | Where-Object { $_.contract_id -eq '279bbdf9-0fe9-4eec-8bb6-46a9df8c72a3' -and $_.status -eq 'CANCELLED' }).Count -ne 1 -or
    @($after.contract_escrow_accounts | Where-Object { $_.contract_id -eq '279bbdf9-0fe9-4eec-8bb6-46a9df8c72a3' -and $_.balance -eq 0 }).Count -ne 1 -or
    @($after.profiles | Where-Object { $_.player_id -eq '3bb5ddbc-5b75-377f-8647-6f25c51f252a' -and $_.balance -eq 1 }).Count -ne 1 -or
    $after.sect_accounts[0].balance -ne 1){throw 'Metadata/số dư sau settlement sai'}
foreach($table in @('building_contracts','contract_escrow_accounts')){
    if((Canonical @($baseline[$table] | Where-Object contract_id -eq '7c9c9656-2602-48b4-98de-447f06e34e97')) -cne
        (Canonical @($after[$table] | Where-Object contract_id -eq '7c9c9656-2602-48b4-98de-447f06e34e97'))){throw "Hợp đồng khác bị thay đổi table=$table"}
}
Write-Output 'FINAL_CLIENT_RECONCILIATION_PASS native_place=1 native_break=1 aborted_intents=3 money=4 admin_receipts=2 settlement=1 immutable_journal=true resources_conserved=true authorities_revoked=true scoped_fence=true'
