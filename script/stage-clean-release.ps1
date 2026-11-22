[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^v?\d+\.\d+\.\d+(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?$')]
    [string]$Version,
    [switch]$ForRelease
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$versionName = $Version.TrimStart('v')
$tag = "v$versionName"
$releaseRoot = Join-Path $root 'releases'
$published = Get-Content -LiteralPath (Join-Path $root 'version.json') -Raw | ConvertFrom-Json
$sdk = $env:ANDROID_HOME
if (!$sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (!$sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$tools = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' -and
        (Test-Path -LiteralPath (Join-Path $_.FullName 'aapt.exe')) -and
        (Test-Path -LiteralPath (Join-Path $_.FullName 'apksigner.bat')) } |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (!$tools) { throw "Android SDK Build Tools are missing under $sdk." }

$tv = @(Get-ChildItem -LiteralPath (Join-Path $root 'Video/build/outputs/apk/noamazon/release') -File -Filter "*-$versionName-armeabi-v7a-release.apk")
if ($tv.Count -ne 1) { throw "Expected one TV ARMv7 Release APK for $tag. Found $($tv.Count)." }
$records = @(
    @{
        Device = 'tv'; Id = 'com.meedx.localcinema.tv'; Abi = 'armeabi-v7a'
        Name = "LocalCinema-TV-$tag.apk"; Path = $tv[0].FullName
    },
    @{
        Device = 'phone'; Id = 'com.meedx.localcinema.manager'; Abi = 'arm64-v8a'
        Name = "LocalCinema-Manager-Phone-$tag.apk"
        Path = Join-Path $root 'Phone/app/build/outputs/apk/base/release/app-base-arm64-v8a-release.apk'
    }
)
$stagingRoot = Join-Path $releaseRoot '_local'
$stageName = "$tag-$(Get-Date -Format 'yyyyMMdd-HHmmss-fff')"
if ($ForRelease) {
    $stagingRoot = $releaseRoot
    $stageName = $tag
}
$destination = Join-Path $stagingRoot $stageName
if (Test-Path -LiteralPath $destination) { throw "Stage already exists: $destination" }

function Get-SignerDigest([string]$Apk) {
    $output = & (Join-Path $tools.FullName 'apksigner.bat') verify --print-certs $Apk
    if ($LASTEXITCODE -ne 0) { throw "Invalid signature: $Apk" }
    $digests = @($output | Select-String '^Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})$')
    if ($digests.Count -ne 1) { throw "Expected a signing certificate: $Apk" }
    $digests[0].Matches[0].Groups[1].Value
}

foreach ($record in $records) {
    if (!(Test-Path -LiteralPath $record.Path -PathType Leaf)) { throw "APK missing: $($record.Path)" }
    $badging = & (Join-Path $tools.FullName 'aapt.exe') dump badging $record.Path
    if ($LASTEXITCODE -ne 0 -or $badging[0] -notmatch "^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'") {
        throw "Could not read APK metadata: $($record.Path)"
    }
    $record.Code = [int]$Matches[2]
    if ($Matches[1] -ne $record.Id -or $Matches[3] -ne $versionName -or
        -not ($badging -match "^native-code: '$($record.Abi)'\s*$")) {
        throw "Wrong package, version, or ABI: $($record.Path)"
    }
    $previous = $published.($record.Device)
    if ($ForRelease -and $record.Code -le $previous.versionCode) {
        throw "A publishable release must use a new versionCode for $($record.Device)."
    }
    $baseline = Join-Path $releaseRoot "v$($previous.versionName)/$($previous.fileName)"
    if (!(Test-Path -LiteralPath $baseline -PathType Leaf) -or
        (Get-FileHash -LiteralPath $baseline -Algorithm SHA256).Hash -ne $previous.sha256) {
        throw "Published signing reference is missing or has changed: $baseline"
    }
    if ((Get-SignerDigest $record.Path) -ne (Get-SignerDigest $baseline)) {
        throw "Signing identity changed for $($record.Device)."
    }
}
if ($records[0].Code -ne $records[1].Code) { throw 'TV and Phone version codes differ.' }

& python (Join-Path $PSScriptRoot 'audit-provider-keys.py') @($records.Path)
if ($LASTEXITCODE -ne 0) { throw 'APK credential audit failed.' }

New-Item -ItemType Directory -Path $stagingRoot -Force | Out-Null
$pending = Join-Path $stagingRoot ('.staging-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $pending | Out-Null
try {
    $checksums = foreach ($record in $records) {
        $hash = (Get-FileHash -LiteralPath $record.Path -Algorithm SHA256).Hash.ToLowerInvariant()
        $target = Join-Path $pending $record.Name
        Copy-Item -LiteralPath $record.Path -Destination $target
        if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash) {
            throw "Copy verification failed: $target"
        }
        "$hash  $($record.Name)"
    }
    $checksums | Set-Content -LiteralPath (Join-Path $pending 'checksums.txt') -Encoding ascii
    Rename-Item -LiteralPath $pending -NewName $stageName
} finally {
    if (Test-Path -LiteralPath $pending) {
        foreach ($name in @($records.Name) + 'checksums.txt') {
            $file = Join-Path $pending $name
            if (Test-Path -LiteralPath $file -PathType Leaf) { Remove-Item -LiteralPath $file }
        }
        Remove-Item -LiteralPath $pending
    }
}
Write-Output "Staged: $destination"
Get-Content -LiteralPath (Join-Path $destination 'checksums.txt')
