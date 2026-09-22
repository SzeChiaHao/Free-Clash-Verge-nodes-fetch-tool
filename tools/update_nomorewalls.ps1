param(
    [string]$Repo = 'D:\Walls\NoMoreWalls',
    [switch]$SkipFetch,
    [switch]$Quiet
)

# NoMoreWalls 长期自动更新脚本
# 按仓库 README.md「本地部署 / 更新」部分的操作顺序执行：
#   1) 备份当前生成的订阅文件
#   2) git pull --depth=1
#   3) git reset --hard origin/master
#   4) (可选) 重新运行 fetch.py 生成最新订阅

$Tools = 'D:\Walls\tools'
$LogDir = Join-Path $Tools 'logs'
New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
$LogFile = Join-Path $LogDir 'update.log'

function Log([string]$msg) {
    $line = "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] $msg"
    Add-Content -Path $LogFile -Value $line
    if (-not $Quiet) { Write-Host $line }
}

Log '======== NoMoreWalls 自动更新开始 ========'
Set-Location $Repo

# 0) 检查远端是否有更新
git fetch --depth=1 origin master 2>&1 | ForEach-Object { Log $_ }
$local = (git rev-parse HEAD).Trim()
$remote = (git rev-parse origin/master).Trim()

if ($local -eq $remote) {
    Log "无需更新（本地 $local 已是最新）"
} else {
    Log "检测到更新：$local -> $remote"

    # 1) 备份当前生成文件（可恢复）
    $bk = "D:\Walls\NoMoreWalls-backup-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    New-Item -ItemType Directory -Path $bk -Force | Out-Null
    Copy-Item "$Repo\list.meta.yml", "$Repo\list.yml", "$Repo\list.txt", "$Repo\list_raw.txt", "$Repo\list_result.csv" -Destination $bk -ErrorAction SilentlyContinue
    Copy-Item "$Repo\snippets" -Destination $bk -Recurse -ErrorAction SilentlyContinue
    Log "已备份到 $bk"

    # 2) 按 README 操作顺序更新
    git pull --depth=1 2>&1 | ForEach-Object { Log $_ }
    git reset --hard origin/master 2>&1 | ForEach-Object { Log $_ }
    Log "已更新到 $(git rev-parse HEAD)"
}

# 3) 可选：重新生成订阅（README 本地部署第 6/9 步）
if (-not $SkipFetch) {
    $conf = Join-Path $Repo 'local_proxy.conf'
    $proxy = if (Test-Path $conf) { (Get-Content $conf -Raw).Trim() } else { '' }
    if ([string]::IsNullOrEmpty($proxy) -or $proxy -eq 'NONE') {
        Log '跳过 fetch.py：local_proxy.conf 为空或为 NONE'
    } else {
        $port = 0
        if ($proxy -match ':(\d+)/?$') { $port = [int]$Matches[1] }
        $listening = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
        if (-not $listening) {
            Log "端口 $port 未监听，跳过 fetch.py（Clash Verge 可能未运行）"
        } else {
            $py = 'C:\Users\szech\AppData\Local\Python\bin\python.exe'
            Log "运行 fetch.py（代理 $proxy）..."
            $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
            $out = Join-Path $LogDir "fetch-$stamp.log"
            $p = Start-Process -FilePath $py -ArgumentList @('-u','fetch.py') -WorkingDirectory $Repo -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError "$out.err" -PassThru
            $p.WaitForExit(1800000) | Out-Null
            if ($p.HasExited) {
                Log "fetch.py 结束，退出码 $($p.ExitCode)（日志 $out）"
            } else {
                Log 'fetch.py 运行超过 30 分钟，强制结束'
                Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
            }
        }
    }
}

Log '======== 更新流程结束 ========'
