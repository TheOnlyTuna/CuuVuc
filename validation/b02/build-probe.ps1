param([Parameter(Mandatory=$true)][string]$JavaHome,[Parameter(Mandatory=$true)][string]$MavenHome)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
[string]$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$env:JAVA_HOME=$JavaHome
Push-Location $repo
try {
    & (Join-Path $MavenHome 'bin/mvn.cmd') -B -f validation/b02/agent/pom.xml package
    if($LASTEXITCODE -ne 0){throw 'Không build được agent B02'}
    New-Item -ItemType Directory target/b02-probe -Force | Out-Null
    [string]$classpath='target/classes;validation/b02/agent/target/b02-agent-1.0-hooks.jar;'+(Get-Content target/b01-classpath.txt -Raw).Trim()
    & (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -cp $classpath -d target/b02-probe validation/b02/probe/BaselineProbe.java
    if($LASTEXITCODE -ne 0){throw 'Không compile được probe B02'}
    & (Join-Path $JavaHome 'bin/jar.exe') --create --file target/BaselineProbe.jar -C target/b02-probe . -C validation/b02/probe plugin.yml
    if($LASTEXITCODE -ne 0){throw 'Không đóng gói được probe B02'}
}finally{Pop-Location}
