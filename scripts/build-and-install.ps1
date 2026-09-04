[CmdletBinding()]
param(
    [switch]$Clean,
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$gradleWrapper = Join-Path $projectRoot 'gradlew.bat'
$apkPath = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$localProperties = Join-Path $projectRoot 'local.properties'

if (-not (Test-Path -LiteralPath $gradleWrapper)) {
    throw "Gradle wrapper was not found at $gradleWrapper"
}

$sdkDirectory = $null
if (Test-Path -LiteralPath $localProperties) {
    $sdkLine = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($sdkLine) {
        $sdkDirectory = ($sdkLine -replace '^sdk\.dir=', '') -replace '\\:', ':' -replace '\\\\', '\'
    }
}
if (-not $sdkDirectory) { $sdkDirectory = $env:ANDROID_HOME }
if (-not $sdkDirectory) { $sdkDirectory = $env:ANDROID_SDK_ROOT }
if (-not $sdkDirectory) { throw 'Android SDK path is missing. Set sdk.dir in local.properties or ANDROID_HOME.' }

$adbPath = Join-Path $sdkDirectory 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adbPath)) { throw "adb.exe was not found at $adbPath" }

$gradleTasks = [string[]]@(':app:assembleDebug')
if ($Clean) { $gradleTasks = [string[]]@('clean', ':app:assembleDebug') }
Push-Location $projectRoot
try {
    & $gradleWrapper @gradleTasks
    if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $apkPath)) { throw "Expected APK was not produced: $apkPath" }

$adbArguments = @()
if ($Serial) {
    if ($Serial -notmatch '^[A-Za-z0-9._:-]+$') { throw 'Serial contains unsupported characters.' }
    $adbArguments += @('-s', $Serial)
}
$deviceCommand = "`"$adbPath`" get-state 2>nul"
if ($Serial) { $deviceCommand = "`"$adbPath`" -s $Serial get-state 2>nul" }
$devices = & cmd.exe /d /c $deviceCommand
if ($LASTEXITCODE -ne 0 -or $devices.Trim() -ne 'device') {
    throw 'No authorized Android device is connected. Enable USB debugging, authorize this computer, then rerun the script.'
}

& $adbPath @adbArguments 'install' '-r' $apkPath
if ($LASTEXITCODE -ne 0) { throw "APK installation failed with exit code $LASTEXITCODE" }

Write-Host "Installed: $apkPath" -ForegroundColor Green
