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
    [string]$Version = "",
    # 发布到 GitHub Releases，并更新仓库里的 version.json（手机的更新通道）
    [switch]$Release,
    [string]$Notes = "",
    # GitHub token；不给就去 git 凭据管理器里取
    [string]$Token = ""
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

if (-not $Release) {
    Write-Host "把它传到手机安装即可覆盖旧版（签名一致，数据保留）。" -ForegroundColor Green
    Write-Host "（加了 -Release 就会发布到 GitHub，让手机 App 自己提示更新）" -ForegroundColor DarkGray
    exit 0
}

# ==================== 发布到 GitHub Releases + 更新 version.json ====================

$Repo     = 'SzeChiaHao/NodeKeeper'
$Tag      = "v$newName"
$Vc       = $newCode
$AssetName = "Walls-$newName-vc$Vc.apk"

if (-not $Token) { $Token = $env:GITHUB_TOKEN }
if (-not $Token) {
    Write-Host "没有 token，尝试从 git 凭据管理器读取……" -ForegroundColor DarkGray
    $cred = ("protocol=https`nhost=github.com`n`n" | git credential fill) 2>$null
    foreach ($line in $cred) { if ($line -like 'password=*') { $Token = $line.Substring(9).Trim() } }
}
if (-not $Token) {
    Write-Host "拿不到 GitHub token。请设环境变量 GITHUB_TOKEN，或先用 git push 让凭据管理器记住。" -ForegroundColor Red
    exit 6
}

$headers = @{
    Authorization = "Bearer $Token"
    Accept        = 'application/vnd.github+json'
    'User-Agent'  = 'Walls-Release-Script'
}

# 已存在同名 tag 就先删掉，保证可重复执行
try {
    $old = Invoke-RestMethod -Method Get -Uri "https://api.github.com/repos/$Repo/releases/tags/$Tag" -Headers $headers
    if ($old) {
        Write-Host "已存在 $Tag 的 release，先删除……" -ForegroundColor Yellow
        Invoke-RestMethod -Method Delete -Uri "https://api.github.com/repos/$Repo/releases/$($old.id)" -Headers $headers | Out-Null
    }
} catch { }

$body = @{
    tag_name   = $Tag
    name       = "v$newName"
    body       = if ($Notes) { $Notes } else { "节点管家 v$newName（versionCode $Vc）" }
    draft      = $false
    prerelease = $false
} | ConvertTo-Json

Write-Host "创建 release $Tag ……" -ForegroundColor Cyan
# PowerShell 5.1 的 -Body 传字符串会按 ANSI 编码，中文会变乱码导致 GitHub 报
# "Problems parsing JSON"，所以这里显式转成 UTF-8 字节
$bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)
$rel = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$Repo/releases" `
    -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $bodyBytes

$uploadUrl = "https://uploads.github.com/repos/$Repo/releases/$($rel.id)/assets?name=$AssetName"
Write-Host "上传安装包 $AssetName ……" -ForegroundColor Cyan
$asset = Invoke-RestMethod -Method Post -Uri $uploadUrl -Headers $headers `
    -ContentType 'application/vnd.android.package-archive' -InFile $apk

$sha = (Get-FileHash -Algorithm SHA256 $apk).Hash.ToLower()
$json = [ordered]@{
    versionName = $newName
    versionCode = $Vc
    apk         = $asset.browser_download_url
    size        = (Get-Item $apk).Length
    sha256      = $sha
    notes       = ($body | ConvertFrom-Json).body
    publishedAt = (Get-Date -Format 'yyyy-MM-dd HH:mm')
}
$jsonPath = Join-Path $Root 'version.json'
# 必须写不带 BOM 的 UTF-8：带 BOM 的话手机端 JSONObject 解析会直接失败
$jsonText = $json | ConvertTo-Json -Depth 3
[System.IO.File]::WriteAllText($jsonPath, $jsonText, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "更新 version.json ……" -ForegroundColor Cyan
Push-Location $Root
try {
    git add version.json
    git -c core.safecrlf=false commit -q -m "release: v$newName (versionCode $Vc)"
    git push -q origin main
} finally { Pop-Location }

Write-Host ""
Write-Host "发布完成：https://github.com/$Repo/releases/tag/$Tag" -ForegroundColor Green
Write-Host "现在手机上已经装了旧版的 App，打开就会提示升级。" -ForegroundColor Green
