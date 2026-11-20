# MuseRecorder 构建脚本
# 用法: powershell -ExecutionPolicy Bypass -File build.ps1 [-Variant debug|release|test]
param(
    [string]$Variant = "release"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$tools = Join-Path (Split-Path -Parent $root) "_tools"

$env:JAVA_HOME = Join-Path $tools "jdk\jdk-17.0.21+9"
$env:ANDROID_HOME = Join-Path $tools "android-sdk"
$gradle = Join-Path $tools "gradle-8.9\bin\gradle.bat"

Write-Host "JAVA_HOME = $env:JAVA_HOME"
Write-Host "Gradle    = $gradle"

switch ($Variant) {
    "test"   { & $gradle -p $root test }
    "debug"  { & $gradle -p $root assembleDebug
               Write-Host "`nAPK: $root\app\build\outputs\apk\debug\app-debug.apk" }
    default  { & $gradle -p $root assembleRelease
               Write-Host "`nAPK: $root\app\build\outputs\apk\release\app-release.apk" }
}
