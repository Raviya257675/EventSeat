param([switch]$Lan, [int]$Port = 8080, [switch]$Build)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location (Join-Path $root 'web')
$previousHost = $env:WEB_HOST
$previousPort = $env:PORT
try {
    if ($Build) {
        & npm.cmd ci --cache (Join-Path $root 'target\npm-cache') --no-fund --no-audit
        if ($LASTEXITCODE) { throw 'npm ci failed.' }
        & npm.cmd run build
        if ($LASTEXITCODE) { throw 'Web build failed.' }
    }
    if (-not (Test-Path 'dist\index.html')) { throw 'Build the frontend first: use -Build.' }
    $env:WEB_HOST = if ($Lan) { '0.0.0.0' } else { '127.0.0.1' }
    $env:PORT = [string]$Port
    & node gateway/server.mjs
    if ($LASTEXITCODE) { throw 'Web gateway failed.' }
} finally {
    $env:WEB_HOST = $previousHost
    $env:PORT = $previousPort
    Pop-Location
}
