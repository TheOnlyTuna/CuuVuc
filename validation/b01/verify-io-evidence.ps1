param([Parameter(Mandatory=$true)][string]$EvidenceDirectory)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
[string]$directory=(Resolve-Path -LiteralPath $EvidenceDirectory).Path
[string[]]$cases=@('payloadbefore','payloadafter','headerbefore','headerafter')
foreach ($case in $cases) {
    [string]$prekill=Get-Content (Join-Path $directory "$case-prekill.jsonl") -Raw
    [string]$killed=Get-Content (Join-Path $directory "$case-killed.jsonl") -Raw
    if ($prekill -cne $killed) { throw "DB thay đổi giữa trước/sau kill case=$case" }
    [object[]]$before=@(Get-Content (Join-Path $directory "$case-killed.jsonl") | ForEach-Object { ConvertFrom-Json $_ })
    [object[]]$after=@(Get-Content (Join-Path $directory "$case-restarted.jsonl") | ForEach-Object { ConvertFrom-Json $_ })
    if ($before.Count -ne $after.Count) { throw "Số bảng khác sau startup case=$case" }
    foreach ($table in $before) {
        [object[]]$actual=@($after | Where-Object table -eq $table.table)
        if ($actual.Count -ne 1) { throw "Thiếu/trùng bảng case=$case table=$($table.table)" }
        if ($table.table -eq 'contract_recovery_fences') {
            [int]$expected=if ($case -eq 'headerafter') {0} else {1}
            if (@($actual[0].rows).Count -ne $expected -or @($table.rows).Count -ne 0) { throw "Fence count sai case=$case" }
            if ($expected -eq 1 -and ($actual[0].rows[0].classification -ne 'MISMATCH' -or $actual[0].rows[0].contract_id -ne '279bbdf9-0fe9-4eec-8bb6-46a9df8c72a3')) { throw "Fence sai phạm vi case=$case" }
        } elseif ($table.table -eq 'contract_state') {
            [int]$increment=if ($case -eq 'headerafter') {0} else {1}
            if ($actual[0].rows[0].revision -ne $table.rows[0].revision+$increment) { throw "Revision sai sau startup case=$case" }
        } elseif (($table.rows | ConvertTo-Json -Depth 30 -Compress) -cne ($actual[0].rows | ConvertTo-Json -Depth 30 -Compress)) {
            throw "Startup thay đổi bảng ngoài fence/revision case=$case table=$($table.table)"
        }
    }
    [string]$marker=Get-Content (Join-Path $directory "$case-marker.txt") -Raw
    if ($case -eq 'payloadafter' -and $marker -notmatch '(?m)^bytesWritten=[1-9][0-9]*\r?$') { throw 'Không có bằng chứng payload đã được ghi' }
    Write-Output "$case FULL_DB_RECONCILIATION_PASS tables=$($before.Count) prekill_equals_killed=true startup_changes_only_expected_fence_and_revision=true"
}
