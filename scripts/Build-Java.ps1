param([string]$JdkHome = 'C:\Program Files\Java\jdk-27', [switch]$RunChecks)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path $root 'target\web-java'
$deps = Join-Path $out 'deps'
$classes = Join-Path $out ('classes-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $deps,$classes | Out-Null
[xml]$pom = Get-Content (Join-Path $root 'pom.xml')
$fx = $pom.project.properties.'javafx.version'
$mysql = ($pom.project.dependencies.dependency | Where-Object artifactId -eq 'mysql-connector-j').version
$artifacts = @("org/openjfx/javafx-base/$fx/javafx-base-$fx-win.jar", "org/openjfx/javafx-graphics/$fx/javafx-graphics-$fx-win.jar", "org/openjfx/javafx-controls/$fx/javafx-controls-$fx-win.jar", "com/mysql/mysql-connector-j/$mysql/mysql-connector-j-$mysql.jar")
foreach ($relative in $artifacts) {
    $file = Join-Path $deps (Split-Path $relative -Leaf)
    if (-not (Test-Path $file)) {
        $cached = Join-Path "$env:USERPROFILE\.m2\repository" $relative
        if (Test-Path $cached) { Copy-Item -LiteralPath $cached -Destination $file }
        else { Invoke-WebRequest -UseBasicParsing -Uri "https://repo.maven.apache.org/maven2/$relative" -OutFile $file }
    }
}
$dependencyCp = ($artifacts | ForEach-Object { Join-Path $deps (Split-Path $_ -Leaf) }) -join ';'
$sources = @(Get-ChildItem (Join-Path $root 'src\main\java'),(Join-Path $root 'src\test\java') -Recurse -Filter *.java | ForEach-Object FullName)
& (Join-Path $JdkHome 'bin\javac.exe') --release 25 -cp $dependencyCp -d $classes @sources
if ($LASTEXITCODE) { throw 'Java compilation failed.' }
# Resource directory stays local; no credentials copied into web/dist or any client image.
$runtimeCp = "$classes;$(Join-Path $root 'src\main\resources');$dependencyCp"
Set-Content -LiteralPath (Join-Path $out 'classpath.txt') -Value $runtimeCp -Encoding UTF8
if ($RunChecks) {
    foreach ($test in 'NetworkReliabilityTestMain','SeatHoldTestMain','HeartbeatTestMain') {
        & (Join-Path $JdkHome 'bin\java.exe') -cp $runtimeCp "com.eventseat.server.$test" --embedded
        if ($LASTEXITCODE) { throw "$test failed." }
    }
    & (Join-Path $JdkHome 'bin\java.exe') --enable-native-access=ALL-UNNAMED -cp $runtimeCp com.eventseat.client.UiRegressionCheck
    if ($LASTEXITCODE) { throw 'UiRegressionCheck failed.' }
}
Write-Host "Compiled Java sources. Classpath: $out\classpath.txt"
