$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

Push-Location (Join-Path $root 'Phone')
try {
    & .\gradlew.bat assembleBaseDebug
    if ($LASTEXITCODE -ne 0) { throw "Phone build failed (exit code $LASTEXITCODE)." }
} finally {
    Pop-Location
}

& (Join-Path $PSScriptRoot 'build-cinema.ps1')
