@echo off
rem 双击运行：抓取节点 + 测速 + 按速度排序 + 同步 Clash Verge
echo ==============================================
echo   免费节点每日更新（约 10-20 分钟，请稍候）
echo ==============================================
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "D:\Walls\tools\daily_auto.ps1"
echo.
if exist "D:\Walls\tools\output\best_nodes.yml" (
    echo [完成] 节点文件：D:\Walls\tools\output\best_nodes.yml
    echo        已同步到 Clash Verge 订阅 auto_best_nodes.yml
) else (
    echo [失败] 请查看日志：D:\Walls\tools\logs\
)
echo.
pause
