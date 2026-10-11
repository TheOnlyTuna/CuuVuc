param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$MavenHome
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[string]$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$env:JAVA_HOME = $JavaHome
Push-Location $repo
try {
    & (Join-Path $MavenHome 'bin/mvn.cmd') -B dependency:build-classpath '-Dmdep.outputFile=target/b01-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Không tạo được classpath từ pom.xml' }
    New-Item -ItemType Directory -Path target/b01-probe -Force | Out-Null
    [string]$classpath = 'target/classes;' + (Get-Content target/b01-classpath.txt -Raw).Trim()
    & (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -cp $classpath -d target/b01-probe validation/b01/probe/CrashProbe.java
    if ($LASTEXITCODE -ne 0) { throw 'Không biên dịch được CrashProbe' }
    & (Join-Path $JavaHome 'bin/jar.exe') --create --file target/CrashProbe.jar -C target/b01-probe . -C validation/b01/probe plugin.yml
    if ($LASTEXITCODE -ne 0) { throw 'Không đóng gói được CrashProbe' }
} finally { Pop-Location }
