param(
    [int]$MaxNodes = 1000
)

# 独立节点抓取包装脚本: 运行 fetch_nodes.py 并输出结果

$Tools = 'D:\Walls\tools'
$LogDir = Join-Path $Tools 'logs'
New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$log = Join-Path $LogDir "scrape-$stamp.log"

$py = 'C:\Users\szech\AppData\Local\Python\bin\python.exe'
$env:PYTHONIOENCODING = 'utf-8'
& $py -u (Join-Path $Tools 'fetch_nodes.py') --max-nodes $MaxNodes *> $log
$code = $LASTEXITCODE

if ($code -eq 0 -and (Test-Path (Join-Path $Tools 'output\my_nodes.yml'))) {
    Write-Host "抓取完成，订阅见 D:\Walls\tools\output\my_nodes.yml（报告 sources_result.csv）"
} else {
    Write-Host "抓取未正常完成，退出码 $code，日志：$log"
}
