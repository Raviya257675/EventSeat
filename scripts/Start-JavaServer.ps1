param([string]$JdkHome = 'C:\Program Files\Java\jdk-27')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
& (Join-Path $PSScriptRoot 'Build-Java.ps1') -JdkHome $JdkHome
$runtimeCp = (Get-Content (Join-Path $root 'target\web-java\classpath.txt') -Raw).Trim()
& (Join-Path $JdkHome 'bin\java.exe') -cp $runtimeCp com.eventseat.server.ServerMain
if ($LASTEXITCODE) { throw 'Java server exited with an error.' }
