param(
    [string]$JdkHome = 'C:\Program Files\Java\jdk-27',
    [string]$MavenRepository = "$env:USERPROFILE\.m2\repository"
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
function Invoke-Jdk([string]$Tool, [string[]]$Arguments) {
    & (Join-Path $JdkHome "bin\$Tool.exe") @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Tool failed ($LASTEXITCODE)" }
}
if (-not [Environment]::Is64BitOperatingSystem -or $env:OS -ne 'Windows_NT') {
    throw 'Build on Windows x64.'
}
$release = Get-Content (Join-Path $JdkHome 'release') -Raw
if ($release -notmatch 'JAVA_VERSION="27[.+"]') {
    throw 'JDK 27 is required.'
}
if ($release -notmatch 'OS_ARCH="(amd64|x86_64)"') { throw 'An x64 JDK is required.' }
[xml]$pom = Get-Content (Join-Path $root 'pom.xml')
$fxVersion = $pom.project.properties.'javafx.version'
$work = Join-Path $root ('target\portable-client\' + [guid]::NewGuid().ToString('N'))
$modules = Join-Path $work 'modules'
$classes = Join-Path $work 'classes'
$inputDir = Join-Path $work 'input'
$destination = Join-Path $root 'dist'
$output = Join-Path $destination 'EventSeat-Client'
if (Test-Path $output) { throw "Output already exists: $output. Move it elsewhere before rebuilding; this script never deletes existing output." }
New-Item -ItemType Directory -Force -Path $modules,$classes,$inputDir,$destination | Out-Null
foreach ($artifact in 'javafx-base','javafx-graphics','javafx-controls') {
    $relative = "org/openjfx/$artifact/$fxVersion/$artifact-$fxVersion-win.jar"
    $cached = Join-Path $MavenRepository $relative
    $jar = Join-Path $modules "$artifact-$fxVersion-win.jar"
    if (Test-Path $cached) { Copy-Item -LiteralPath $cached -Destination $jar }
    else {
        Invoke-WebRequest -UseBasicParsing -Uri "https://repo.maven.apache.org/maven2/$relative" -OutFile $jar
    }
    Invoke-Jdk 'jar' @('--describe-module','--file',$jar) | Out-Null
}
# Explicit source allowlist: no server, repository, database utilities or resources.
$sources = @('EventSeatApp.java','ServerConnection.java','ClientConfiguration.java') | ForEach-Object {
    Join-Path $root "src\main\java\com\eventseat\client\$_"
}
Invoke-Jdk 'javac' (@('--release','25','--module-path',$modules,'--add-modules','javafx.controls','-d',$classes) + $sources)
$clientJar = Join-Path $inputDir 'eventseat-client.jar'
Invoke-Jdk 'jar' @('--create','--file',$clientJar,'--main-class','com.eventseat.client.EventSeatApp','-C',$classes,'.')
$entries = & (Join-Path $JdkHome 'bin\jar.exe') tf $clientJar
if ($LASTEXITCODE -ne 0) { throw 'Could not audit client JAR.' }
foreach ($entry in $entries) {
    if ($entry -notmatch '^(META-INF/|META-INF/MANIFEST.MF|com/|com/eventseat/|com/eventseat/client/|com/eventseat/client/(EventSeatApp|ServerConnection|ClientConfiguration)(\$[^/]+)?\.class)$') {
        throw "Unexpected client JAR entry: $entry"
    }
}
Invoke-Jdk 'jpackage' @('--type','app-image','--name','EventSeat-Client','--app-version','1.0',
    '--dest',$destination,'--input',$inputDir,'--main-jar','eventseat-client.jar',
    '--main-class','com.eventseat.client.EventSeatApp','--module-path',$modules,
    '--add-modules','javafx.controls','--java-options','--add-modules=javafx.controls',
    '--java-options','--enable-native-access=javafx.graphics')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Start-Client.bat') -Destination $output
Copy-Item -LiteralPath (Join-Path $root 'PORTABLE_CLIENT.md') -Destination $output
foreach ($file in 'EventSeat-Client.exe','app\eventseat-client.jar','runtime\bin\server\jvm.dll','runtime\lib\modules') {
    if (-not (Test-Path (Join-Path $output $file))) { throw "Missing packaged file: $file" }
}
$runtimeEntries = & (Join-Path $JdkHome 'bin\jimage.exe') list (Join-Path $output 'runtime\lib\modules')
if ($LASTEXITCODE -ne 0) { throw 'Could not audit bundled runtime.' }
foreach ($required in 'Module: javafx.base','Module: javafx.controls','Module: javafx.graphics','glass.dll','prism_d3d.dll','javafx_font.dll') {
    if (-not ($runtimeEntries | Select-String -SimpleMatch $required)) { throw "Bundled runtime missing: $required" }
}
$forbidden = Get-ChildItem $output -Recurse -File | Where-Object { $_.Name -match 'db\.properties|mysql|password|credential|^\.env' }
if ($forbidden) { throw 'Unexpected sensitive/server files in distribution.' }
Write-Host "Portable client built and audited: $output"
Write-Host "Build staging: $work"
