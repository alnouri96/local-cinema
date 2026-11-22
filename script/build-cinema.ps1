param(
    [switch]$NativeOnly,
    [switch]$Install,
    [string]$Device = $env:LOCAL_CINEMA_TV_ADB
)
$ErrorActionPreference = 'Stop'
if ($Install -and ($NativeOnly -or !$Device)) { throw 'Specify a TV device for debug installation and omit -NativeOnly.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$cachePath = Join-Path $projectRoot '.build-cache'
New-Item -ItemType Directory -Path $cachePath -Force | Out-Null
$nativeApk = Join-Path $cachePath 'nova-upstream-6.4.51-armeabi-v7a.apk'
$expectedHash = '2C9E0265903F8AE9DCE1F96F6474CF76EF0EAB08F6C76B99AE1B064E6EF440D6'
if (!(Test-Path -LiteralPath $nativeApk)) {
    $release = Invoke-RestMethod 'https://api.github.com/repos/nova-video-player/aos-AVP/releases/tags/v6.4.51'
    $asset = $release.assets | Where-Object name -like '*armeabi-v7a-release.apk' | Select-Object -First 1
    if (!$asset) { throw 'Matching official native-engine APK not found.' }
    Invoke-WebRequest $asset.browser_download_url -OutFile $nativeApk
}
if ((Get-FileHash -LiteralPath $nativeApk -Algorithm SHA256).Hash -ne $expectedHash) { throw 'Upstream APK checksum mismatch.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($nativeApk)
try {
    foreach ($entry in $archive.Entries) {
        if ($entry.FullName -notmatch '^lib/armeabi-v7a/([a-zA-Z0-9_.+-]+\.so)$') { continue }
        $libraryName = $Matches[1]
        if ($libraryName -in @('libsentry.so','libsentry-android.so')) { continue }
        $targetDir = Join-Path $projectRoot 'MediaLib/libs/armeabi-v7a'
        New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $targetDir $libraryName), $true)
    }
} finally { $archive.Dispose() }
if ($NativeOnly) { return }
if (!$env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
Push-Location (Join-Path $projectRoot 'Video')
try {
    & java -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain -PprebuiltNative -Puniversal assembleNoamazonDebug
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }
    $output = Join-Path $projectRoot 'Video/build/outputs/apk/noamazon/debug'
    $metadata = Get-Content -LiteralPath (Join-Path $output 'output-metadata.json') -Raw | ConvertFrom-Json
    if (@($metadata.elements).Count -ne 1) { throw 'Expected one debug APK.' }
    $apk = Join-Path $output $metadata.elements[0].outputFile
    if (!(Test-Path -LiteralPath $apk -PathType Leaf)) { throw "APK missing: $apk" }
    if ($Install) {
        & python (Join-Path $PSScriptRoot 'audit-provider-keys.py') $apk
        if ($LASTEXITCODE -ne 0) { throw 'APK credential audit failed.' }
        if ($Device.Contains(':')) { & adb connect $Device }
        & adb -s $Device install -r --user 0 $apk
        if ($LASTEXITCODE -ne 0) { throw 'Installation failed.' }
    }
    Write-Output $apk
} finally { Pop-Location }
