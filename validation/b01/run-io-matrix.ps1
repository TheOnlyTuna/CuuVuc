param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$PostgresBin,
    [Parameter(Mandatory=$true)][string]$BundleDirectory,
    [Parameter(Mandatory=$true)][string]$ProbeJar,
    [Parameter(Mandatory=$true)][string]$RunId
)
. (Join-Path $PSScriptRoot 'matrix-support.ps1')
if (-not $RunId.StartsWith('io')) { throw 'RunId I/O phải bắt đầu bằng io' }
[string]$agent = Join-Path $PSScriptRoot 'io-agent/target/b01-io-agent-1.0.jar'
[string[]]$cases = @('payloadbefore','payloadafter','headerbefore','headerafter')
[string[]]$phases = @('PAYLOAD_BEFORE_WRITE','PAYLOAD_AFTER_WRITE','HEADER_BEFORE_WRITE','HEADER_AFTER_WRITE')
[System.Diagnostics.Process]$process = $null
[string]$runtime = ''
function Snapshot-IoDatabase([string]$database, [string]$label) {
    & (Join-Path $repo 'validation/v08/snapshot-database.ps1') -PostgresBin $PostgresBin -HostName 127.0.0.1 -Port 55439 -DatabaseName $database -UserName cuuvuc_test -OutputFile (Join-Path $output "$label.jsonl") | Out-Null
}
function Snapshot-IoWorld([string]$runtime, [string]$label) {
    Get-ChildItem (Join-Path $runtime 'world/dimensions/minecraft/cv_sky/region') -File | Where-Object { $_.Extension -in @('.mca','.mcc') } |
        Sort-Object FullName | ForEach-Object {
            [IO.FileStream]$stream=[IO.File]::Open($_.FullName,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
            try { [pscustomobject]@{path=[IO.Path]::GetRelativePath($runtime,$_.FullName);length=$stream.Length;sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream))} }
            finally { $stream.Dispose() }
        } | ConvertTo-Json -Depth 3 | Set-Content (Join-Path $output "$label-world-files.json") -Encoding utf8
}
try {
    for ([int]$index=0; $index -lt $cases.Count; $index++) {
        [string]$case=$cases[$index]
        [string]$database="cv_b01_${RunId}_$case"
        $runtime=Join-Path $repo "run-b01-${RunId}-$case"
        & (Join-Path $repo 'scripts/restore-server.ps1') -BundleDirectory $BundleDirectory -ServerDirectory $runtime -PostgresBin $PostgresBin -HostName 127.0.0.1 -Port 55439 -DatabaseName $database -UserName cuuvuc_test | Add-Content (Join-Path $output 'restore.txt')
        Copy-Item (Join-Path $repo 'target/CuuVucCore-0.8.0-SNAPSHOT.jar') (Join-Path $runtime 'plugins/CuuVucCore-0.8.0-SNAPSHOT.jar')
        Copy-Item -LiteralPath $ProbeJar -Destination (Join-Path $runtime 'plugins/CrashProbe.jar')
        [string]$properties=Join-Path $runtime 'server.properties'
        (Get-Content $properties -Raw) -replace '(?m)^server-port=.*$','server-port=25593' -replace '(?m)^server-ip=.*$','server-ip=127.0.0.1' | Set-Content $properties -Encoding utf8
        Sql $database "UPDATE building_contracts SET expires_at=clock_timestamp()+interval '1 day'" | Out-Null
        [string]$jar=Join-Path $runtime 'paper.jar'
        $process=Start-Process -FilePath (Join-Path $JavaHome 'bin/java.exe') -ArgumentList @('-Dterminal.jline=false','-Dterminal.ansi=false','-Xms256M','-Xmx768M',('"-javaagent:'+$agent+'"'),'-jar',('"'+$jar+'"'),'--nogui') -WorkingDirectory $runtime -WindowStyle Hidden -RedirectStandardOutput (Join-Path $output "$case-before.stdout.log") -RedirectStandardError (Join-Path $output "$case-before.stderr.log") -PassThru
        Wait-FileText (Join-Path $output "$case-before.stdout.log") 'cuuvuc_database_ready'
        Wait-FileText (Join-Path $runtime 'io-probe/transformed.txt') 'RegionFile'
        Step $runtime 'baseline-clear' 'CLEAR' | Out-Null
        Step $runtime 'baseline-save' 'BASELINE_FLUSH' | Set-Content (Join-Path $output "$case-baseline-flush.txt")
        Snapshot-IoDatabase $database "$case-baseline"
        Snapshot-IoWorld $runtime "$case-baseline"
        Step $runtime 'place' 'JPLACE' | Set-Content (Join-Path $output "$case-fixture.txt")
        Snapshot-IoDatabase $database "$case-prekill"
        Set-Content (Join-Path $runtime 'io-probe/arm.txt') $phases[$index] -Encoding utf8
        Set-Content (Join-Path $runtime 'plugins/CrashProbe/command.txt') 'crash-save SAVE' -Encoding utf8
        Wait-FileText (Join-Path $runtime 'io-probe/marker.txt') ('phase='+$phases[$index])
        [string]$marker=Get-Content (Join-Path $runtime 'io-probe/marker.txt') -Raw
        if ($marker -notmatch "(?m)^pid=$($process.Id)\r?$" -or $marker -notmatch 'RegionFile.write') { throw 'Marker không khớp PID hoặc stack ghi thật' }
        Copy-Item (Join-Path $runtime 'io-probe/marker.txt') (Join-Path $output "$case-marker.txt")
        Kill-TestProcess $process $runtime "$case-fault"
        Snapshot-IoWorld $runtime "$case-killed"
        Snapshot-IoDatabase $database "$case-killed"
        [string]$region=Join-Path $runtime 'world/dimensions/minecraft/cv_sky/region/r.0.0.mca'
        [string]$archive=Join-Path $repo "backups/b01-$RunId-$case-killed"
        New-Item -ItemType Directory -Path $archive | Out-Null
        Copy-Item $region (Join-Path $archive 'r.0.0.mca')
        & (Join-Path $PostgresBin 'pg_dump.exe') --no-password -h 127.0.0.1 -p 55439 -U cuuvuc_test -d $database -Fc -f (Join-Path $archive 'database.dump')
        if ($LASTEXITCODE -ne 0) { throw "Không backup DB sau kill: $database" }
        Get-FileHash (Join-Path $archive 'r.0.0.mca'),(Join-Path $archive 'database.dump') -Algorithm SHA256 | Format-List | Out-File (Join-Path $output "$case-killed-backup-hashes.txt")
        [string]$before=State $database
        $process=Launch $runtime "$case-restart"
        [string]$observed=Step $runtime 'observe-restart' 'OBSERVE'
        [string]$after=State $database
        if ($before -cne $after) { throw "Startup thay đổi tài sản/audit case=$case" }
        Set-Content (Join-Path $output "$case-state.json") $after -Encoding utf8
        Set-Content (Join-Path $output "$case-world.txt") $observed -Encoding utf8
        [string]$expected=if ($case -eq 'headerafter') {'CONFIRMED'} else {'MISMATCH'}
        [string]$classification=Sql $database "SELECT coalesce((SELECT classification FROM contract_recovery_fences WHERE contract_id='279bbdf9-0fe9-4eec-8bb6-46a9df8c72a3'),'CONFIRMED')"
        if ($classification -ne $expected) { throw "Phân loại khác dự kiến case=$case expected=$expected actual=$classification" }
        if ($expected -eq 'MISMATCH') { Step $runtime 'fence-check' 'FENCE_CHECK' | Set-Content (Join-Path $output "$case-denied.txt") }
        [string]$other=Sql $database "SELECT count(*) FROM contract_recovery_fences WHERE contract_id<>'279bbdf9-0fe9-4eec-8bb6-46a9df8c72a3'"
        if ($other -ne '0') { throw 'Fence lan sang hợp đồng không liên quan' }
        & (Join-Path $PostgresBin 'psql.exe') -X --no-password -h 127.0.0.1 -p 55439 -U cuuvuc_test -d $database -At -v ON_ERROR_STOP=1 -f (Join-Path $PSScriptRoot 'io-assertions.sql') | Set-Content (Join-Path $output "$case-assertions.txt")
        if ($LASTEXITCODE -ne 0) { throw "Assertions I/O thất bại database=$database" }
        Snapshot-IoDatabase $database "$case-restarted"
        Snapshot-IoWorld $runtime "$case-restarted"
        Copy-Item (Get-ChildItem (Join-Path $runtime 'plugins/CuuVucCore/recovery') -Filter '*.tsv' | Sort-Object LastWriteTime | Select-Object -Last 1).FullName (Join-Path $output "$case-recovery.tsv")
        Step $runtime 'cleanup-stop' 'STOP' | Out-Null
        if (-not $process.WaitForExit(45000)) { throw "Soft stop hết hạn runtime=$runtime" }
        Add-Content (Join-Path $output 'completed.txt') "$case HARD_KILL_PASS phase=$($phases[$index]) classification=$classification other_contract_unfenced=true $($observed.Trim())"
    }
} finally {
    if ($null -ne $process -and -not $process.HasExited) { Kill-TestProcess $process $runtime 'error-cleanup' }
}
Get-FileHash (Join-Path $repo 'target/CuuVucCore-0.8.0-SNAPSHOT.jar'),$ProbeJar,$agent,(Join-Path $PSScriptRoot 'io-agent/target/b01-io-agent-1.0-hooks.jar'),(Join-Path $runtime 'paper.jar'),(Join-Path $runtime 'versions/26.3/paper-26.3.jar') -Algorithm SHA256 | Format-List | Out-File (Join-Path $output 'binary-hashes.txt')
& (Join-Path $PSScriptRoot 'verify-io-evidence.ps1') -EvidenceDirectory $output | Set-Content (Join-Path $output 'full-db-reconciliation.txt') -Encoding utf8
