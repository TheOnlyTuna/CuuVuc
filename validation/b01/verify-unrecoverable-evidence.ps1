param([Parameter(Mandatory=$true)][string]$EvidenceDirectory)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
[string]$directory=(Resolve-Path -LiteralPath $EvidenceDirectory).Path
function Read-Snapshot([string]$label) {
    [hashtable]$tables=@{}
    Get-Content (Join-Path $directory "$label.jsonl") | ForEach-Object {
        [object]$table=ConvertFrom-Json $_
        if($tables.ContainsKey($table.table)) {throw "Bảng trùng label=$label table=$($table.table)"}
        $tables[$table.table]=@($table.rows)
    }
    return $tables
}
function Canonical([object]$value) {return ConvertTo-Json -InputObject $value -Depth 50 -Compress}
function Money([hashtable]$tables) {
    return [long](($tables.profiles.balance + $tables.sect_accounts.balance + $tables.contract_escrow_accounts.balance | Measure-Object -Sum).Sum)
}
function Resources([hashtable]$tables) {
    return Canonical @(@($tables.inventory + $tables.project_inventory + $tables.stage_inventory) | Group-Object resource_id | Sort-Object Name | ForEach-Object {
        [pscustomobject]@{resource=$_.Name;quantity=[long](($_.Group.quantity | Measure-Object -Sum).Sum)}
    })
}
foreach($case in @('holdbefore','holdafter','settlebefore','settleafter')) {
    [hashtable]$before=Read-Snapshot "$case-before-approval"
    [hashtable]$after=Read-Snapshot "$case-committed"
    [hashtable]$intact=Read-Snapshot "$case-intact"
    if($before.Count -ne 39 -or $after.Count -ne 39) {throw "Thiếu bảng case=$case"}
    [object]$incident=$after.unrecoverable_cases[0]
    [object]$decision=$after.unrecoverable_decisions[0]
    [object]$entry=$after.contract_block_journal[0]
    [object[]]$receipts=@($after.operations | Where-Object request_id -eq $decision.request_id)
    if($after.unrecoverable_cases.Count -ne 1 -or $after.unrecoverable_decisions.Count -ne 1 -or $decision.outcome -ne 'APPROVED' -or
        $decision.case_id -ne $incident.case_id -or $decision.actor_id -eq $incident.proposer_id -or $decision.authority_revision -ne 3 -or
        $receipts.Count -ne 1 -or $receipts[0].actor_id -ne $decision.actor_id -or $receipts[0].result -eq 'pending' -or
        $receipts[0].fingerprint -ne ('recovery:unrecoverable:approve:'+$incident.case_id)) {throw "Quyết định/receipt quản trị sai case=$case"}
    if($after.operations.Count -ne $before.operations.Count+1 -or @($after.operations | Where-Object request_id -eq $entry.event_id).Count -ne 0 -or
        @($intact.operations | Where-Object request_id -eq $entry.event_id).Count -ne 1) {throw "Receipt gốc bị tạo lại hoặc có receipt thừa case=$case"}
    if((Canonical $intact.contract_block_journal) -cne (Canonical $after.contract_block_journal) -or
        (Canonical $before.contract_block_journal) -cne (Canonical $after.contract_block_journal) -or
        (Canonical $before.unrecoverable_cases) -cne (Canonical $after.unrecoverable_cases) -or
        (Canonical $before.contract_recovery_fences) -cne (Canonical $after.contract_recovery_fences) -or
        $entry.state -ne 'APPLIED' -or $after.contract_recovery_fences.Count -ne 1 -or
        $after.contract_recovery_fences[0].classification -ne 'UNRECOVERABLE' -or $after.contract_recovery_fences[0].contract_id -ne $incident.contract_id) {throw "Journal/hồ sơ/fence đổi case=$case"}
    [object[]]$auditBefore=@($before.building_history | Where-Object event_type -in @('PLACE','BREAK','COMPLETE'))
    [object[]]$auditAfter=@($after.building_history | Where-Object event_type -in @('PLACE','BREAK','COMPLETE'))
    if($auditAfter.Count -ne 1 -or (Canonical $auditBefore) -cne (Canonical $auditAfter) -or $auditAfter[0].event_id -ne $entry.audit_event_id) {throw "Audit thi công bị tạo/sửa case=$case"}
    if((Money $before) -ne 4 -or (Money $after) -ne 4 -or (Resources $before) -cne (Resources $after) -or
        (Canonical $before.item_tokens) -cne (Canonical $after.item_tokens) -or (Canonical $before.inventory) -cne (Canonical $after.inventory)) {throw "Tài sản tăng/mất case=$case"}
    [string[]]$mutable=@('operations','unrecoverable_decisions')
    if($case.StartsWith('settle')) {
        $mutable+=@('profiles','sects','sect_history','sect_accounts','contract_escrow_accounts','contract_escrow_ledger','ledger','resource_ledger','stage_inventory','project_inventory','projects','project_stages','project_history','building_contracts','building_history','contract_state')
        [object[]]$settlement=@($after.contract_escrow_ledger | Where-Object request_id -eq $decision.request_id)
        [object[]]$wallet=@($after.ledger | Where-Object request_id -eq $decision.request_id)
        [object[]]$resources=@($after.resource_ledger | Where-Object request_id -eq $decision.request_id)
        [object]$job=@($after.building_contracts | Where-Object contract_id -eq $incident.contract_id)[0]
        if($settlement.Count -ne 1 -or $settlement[0].kind -ne 'RECOVERY_SETTLEMENT' -or $settlement[0].delta -ne -2 -or
            $wallet.Count -ne 2 -or ($wallet.delta | Measure-Object -Sum).Sum -ne 2 -or
            @($wallet | Where-Object { $_.reason -eq ('RECOVERY_RETURN:'+$incident.contract_id) -and $_.delta -eq 1 -and $_.sect_id -eq $job.funding_sect_id }).Count -ne 1 -or
            @($wallet | Where-Object { $_.reason -eq ('RECOVERY_COMPENSATION_NOT_WORK_PAYMENT:'+$incident.contract_id) -and $_.delta -eq 1 -and $_.player_id -eq $job.builder_id }).Count -ne 1 -or
            $resources.Count -ne 4 -or $job.status -ne 'CANCELLED' -or
            @($after.stage_inventory | Where-Object { $_.stage_id -eq $job.project_stage_id -and $_.quantity -ne 0 }).Count -ne 0) {throw "Phân bổ escrow/dự trữ sai case=$case"}
        foreach($group in @($resources | Group-Object resource_id)) {
            if(($group.Group.delta | Measure-Object -Sum).Sum -ne 0 -or @($group.Group | Where-Object kind -ne 'TRANSFER').Count -ne 0) {throw "Ledger tài nguyên không cân bằng case=$case resource=$($group.Name)"}
        }
        foreach($table in @('building_contracts','contract_escrow_accounts')) {
            if((Canonical @($before[$table] | Where-Object contract_id -ne $incident.contract_id)) -cne (Canonical @($after[$table] | Where-Object contract_id -ne $incident.contract_id))) {throw "Hợp đồng khác bị đổi case=$case table=$table"}
        }
    }
    foreach($table in $before.Keys) {
        if($table -notin $mutable -and (Canonical $before[$table]) -cne (Canonical $after[$table])) {throw "Bảng ngoài phạm vi đổi case=$case table=$table"}
    }
    foreach($pair in @(@('killed','restarted'),@('committed','replayed'))) {
        if((Get-Content (Join-Path $directory "$case-$($pair[0]).jsonl") -Raw) -cne (Get-Content (Join-Path $directory "$case-$($pair[1]).jsonl") -Raw)) {throw "Snapshot đổi case=$case pair=$pair"}
    }
    if($case.EndsWith('before') -and (Get-Content (Join-Path $directory "$case-before-approval.jsonl") -Raw) -cne (Get-Content (Join-Path $directory "$case-killed.jsonl") -Raw)) {throw "Precommit không rollback case=$case"}
    if($case.EndsWith('before')) {
        [object]$marker=Get-Content (Join-Path $directory "$case-precommit-marker.json") -Raw | ConvertFrom-Json
        if($marker.backend_pid -le 0 -or $marker.wait -ne 'PgSleep' -or $marker.query -notlike 'INSERT INTO unrecoverable_decisions*' -or
            (Get-Content (Join-Path $directory "$case-unrecoverable.stdout.log") -Raw) -match 'canceling statement due to statement timeout') {throw "Marker precommit không hợp lệ case=$case"}
    }
    if((Get-Content (Join-Path $directory "$case-world.txt") -Raw).Trim() -ne 'action=OBSERVE world=COBBLESTONE') {throw "World đổi case=$case"}
    Write-Output "$case UNRECOVERABLE_EVIDENCE_PASS tables=39 money=4 resources_conserved=true original_receipt_absent=true audit_unchanged=true scoped_fence=true replay_once=true"
}
