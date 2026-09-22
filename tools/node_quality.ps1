param(
    [string]$Source = 'D:\Walls\NoMoreWalls\list.meta.yml',
    [int]$Top = 12,
    [int]$MaxNodes = 300,
    [switch]$Quick,
    [switch]$SkipVideo
)

# 节点质量测试包装脚本：运行 node_quality.py 并把结果同步到 Clash Verge

$Tools = 'D:\Walls\tools'
$LogDir = Join-Path $Tools 'logs'
New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$log = Join-Path $LogDir "node_quality-$stamp.log"

$py = 'C:\Users\szech\AppData\Local\Python\bin\python.exe'
$env:PYTHONIOENCODING = 'utf-8'
$args = @(
    'node_quality.py',
    '--source', $Source,
    '--top', "$Top",
    '--max-nodes', "$MaxNodes",
    '--out', (Join-Path $Tools 'output')
)
if ($Quick) { $args += '--quick' }
if ($SkipVideo) { $args += '--skip-video' }

& $py -u @args *> $log
$code = $LASTEXITCODE

$best = Join-Path $Tools 'output\best_nodes.yml'
if ($code -eq 0 -and (Test-Path $best)) {
    Write-Host "测试完成，结果见 $best（报告 report.csv / report.md）"
} else {
    Write-Host "测试未正常完成，退出码 $code，日志：$log"
}
