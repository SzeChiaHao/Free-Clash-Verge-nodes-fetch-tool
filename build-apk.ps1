# 一键打手机安装包：可选自动升版本号 -> 编译 release -> 复制到「下载」目录
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File D:\Walls\build-apk.ps1              # 版本号 +1 再编译
#   powershell -ExecutionPolicy Bypass -File D:\Walls\build-apk.ps1 -NoBump      # 版本号不变，只重编
#   powershell -ExecutionPolicy Bypass -File D:\Walls\build-apk.ps1 -Version 2.0 # 指定版本名（同时 versionCode +1）
#
# 说明：Android 覆盖安装要求新包的 versionCode 大于已装的版本，所以默认会自增。

param(
    [switch]$NoBump,
    [string]$Version = ""
)

$ErrorActionPreference = 'Stop'

$Root    = 'D:\Walls'
$Android = Join-Path $Root 'android'
$Gradle  = Join-Path $Android 'gradlew.bat'
$Bkts    = Join-Path $Android 'app\build.gradle.kts'
$Jdk     = Join-Path $Root 'build\jdk\jdk-17.0.13+11'
$OutDir  = Join-Path $Android 'app\build\outputs\apk\release'

if (-not (Test-Path $Gradle)) { Write-Host "找不到 $Gradle" -ForegroundColor Red; exit 2 }
if (-not (Test-Path $Bkts))   { Write-Host "找不到 $Bkts" -ForegroundColor Red; exit 2 }

# 本机没设 JAVA_HOME，用工程里自带的 JDK 17
if (-not $env:JAVA_HOME -or -not (Test-Path $env:JAVA_HOME)) {
    if (Test-Path $Jdk) {
        $env:JAVA_HOME = $Jdk
    } else {
        Write-Host "没有可用的 JDK，请设 JAVA_HOME" -ForegroundColor Red; exit 3
    }
}
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Write-Host "JDK: $env:JAVA_HOME" -ForegroundColor DarkGray

# ---- 版本号 ----
$text = Get-Content -Raw -Encoding UTF8 $Bkts
$codeMatch = [regex]::Match($text, 'versionCode\s*=\s*(\d+)')
$nameMatch = [regex]::Match($text, 'versionName\s*=\s*"([^"]*)"')
if (-not $codeMatch.Success -or -not $nameMatch.Success) {
    Write-Host "在 build.gradle.kts 里找不到 versionCode / versionName" -ForegroundColor Red; exit 4
}
$oldCode = [int]$codeMatch.Groups[1].Value
$oldName = $nameMatch.Groups[1].Value
$newCode = if ($NoBump) { $oldCode } else { $oldCode + 1 }
$newName = if ($Version -ne "") { $Version } else { $oldName }

if ($newCode -ne $oldCode -or $newName -ne $oldName) {
    $text = $text -replace 'versionCode\s*=\s*\d+', "versionCode = $newCode"
    $text = $text -replace 'versionName\s*=\s*"[^"]*"', "versionName = `"$newName`""
    Set-Content -Path $Bkts -Value $text -Encoding UTF8 -NoNewline
    Write-Host "版本：$oldName ($oldCode) -> $newName ($newCode)" -ForegroundColor Cyan
} else {
    Write-Host "版本：$oldName ($oldCode) 不变" -ForegroundColor DarkGray
}

# ---- 编译 ----
Write-Host "开始编译 release……" -ForegroundColor Cyan
Push-Location $Android
try {
    & $Gradle assembleRelease --no-daemon
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($code -ne 0) { Write-Host "编译失败（退出码 $code）" -ForegroundColor Red; exit $code }

# ---- 复制到「下载」目录，文件名带版本 ----
$apk = Join-Path $OutDir 'app-release.apk'
if (-not (Test-Path $apk)) { Write-Host "没找到产物 $apk" -ForegroundColor Red; exit 5 }
$dest = Join-Path (Join-Path $env:USERPROFILE 'Downloads') "Walls-$newName.apk"
Copy-Item -Force $apk $dest

$size = [math]::Round((Get-Item $dest).Length / 1MB, 2)
Write-Host ""
Write-Host "完成：$dest  ($size MB)" -ForegroundColor Green
Write-Host "把它传到手机安装即可覆盖旧版（签名一致，数据保留）。" -ForegroundColor Green
