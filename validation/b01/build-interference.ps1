param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$MavenHome
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
[string]$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$env:JAVA_HOME=$JavaHome
Push-Location $repo
try {
    & (Join-Path $MavenHome 'bin/mvn.cmd') -B -ntp dependency:build-classpath '-Dmdep.outputFile=target/b01-classpath.txt'
    if ($LASTEXITCODE -ne 0) {throw 'Không tạo được classpath interference từ pom.xml'}
    New-Item -ItemType Directory target/b01-interference -Force | Out-Null
    [string]$classpath=(Get-Content target/b01-classpath.txt -Raw).Trim()
    & (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -cp $classpath -d target/b01-interference validation/b01/interference/InterferenceProbe.java
    if ($LASTEXITCODE -ne 0) {throw 'Không biên dịch được InterferenceProbe'}
    & (Join-Path $JavaHome 'bin/jar.exe') --create --file target/InterferenceProbe.jar -C target/b01-interference . -C validation/b01/interference plugin.yml
    if ($LASTEXITCODE -ne 0) {throw 'Không đóng gói được InterferenceProbe'}
} finally {Pop-Location}
